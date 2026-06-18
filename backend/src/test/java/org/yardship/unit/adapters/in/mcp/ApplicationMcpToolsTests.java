package org.yardship.unit.adapters.in.mcp;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.in.mcp.ApplicationMcpTools;
import org.yardship.adapters.in.mcp.ApplicationMcpTools.ScrapeTargetArg;
import org.yardship.adapters.in.mcp.ApplicationView;
import org.yardship.core.domain.primitives.ScrapeTarget;
import org.yardship.core.domain.primitives.Side;
import org.yardship.core.domain.primitives.TargetResult;
import org.yardship.core.domain.primitives.Version;
import org.yardship.core.domain.primitives.VersionApplication;
import org.yardship.core.ports.in.ApplicationVersionPort;
import org.yardship.core.ports.in.Outcome;
import org.yardship.core.ports.in.ScrapeStatus;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the MCP driving adapter. Mirrors MetricsControllerTests: the
 * inbound port is mocked and the adapter is exercised as a CDI bean ({@code sut}).
 *
 * The adapter is a thin projection over {@link ApplicationVersionPort#getApplications()}:
 *   - list_outdated_applications filters by drift severity threshold,
 *   - get_application does an exact-name lookup,
 *   - both project {@link VersionApplication} into the {@link ApplicationView} view record.
 */
@QuarkusTest
public class ApplicationMcpToolsTests {

    @InjectMock
    private ApplicationVersionPort applicationVersionPort;

    @Inject
    private ApplicationMcpTools sut;

    private final VersionApplication majorBehind = new VersionApplication("argo-cd",
            new Version("1.1.1"), new Version("2.2.2"));
    private final VersionApplication minorBehind = new VersionApplication("grafana",
            new Version("2.1.0"), new Version("2.2.0"));
    private final VersionApplication current = new VersionApplication("gitea",
            new Version("2.0.0"), new Version("2.0.0"));

    private void stubApplications() {
        when(applicationVersionPort.getApplications())
                .thenReturn(List.of(majorBehind, minorBehind, current));
    }

    @Test
    void listOutdatedApplications_default_excludesCurrentApps() {
        stubApplications();

        List<ApplicationView> result = sut.list_outdated_applications(null);

        List<String> names = result.stream().map(ApplicationView::name).toList();
        assertTrue(names.contains("argo-cd"), "expected major-behind app: " + names);
        assertTrue(names.contains("grafana"), "expected minor-behind app: " + names);
        assertFalse(names.contains("gitea"), "current app must be excluded: " + names);
        assertEquals(2, result.size());
    }

    @Test
    void listOutdatedApplications_majorThreshold_returnsOnlyMajorDriftApps() {
        stubApplications();

        List<ApplicationView> result = sut.list_outdated_applications(Version.Diff.MAJOR);

        assertEquals(1, result.size());
        assertEquals("argo-cd", result.getFirst().name());
    }

    @Test
    void listOutdatedApplications_projectsViewFieldsCorrectly() {
        stubApplications();

        ApplicationView view = sut.list_outdated_applications(Version.Diff.MAJOR).getFirst();

        assertEquals("argo-cd", view.name());
        assertEquals("1.1.1", view.current());
        assertEquals("2.2.2", view.latest());
        assertTrue(view.outdated(), "major-behind app must be outdated");
        assertEquals("MAJOR", view.drift());
    }

    @Test
    void getApplication_returnsView_forKnownName() {
        stubApplications();

        ApplicationView view = sut.get_application("grafana");

        assertEquals("grafana", view.name());
        assertEquals("2.1.0", view.current());
        assertEquals("2.2.0", view.latest());
        assertTrue(view.outdated());
        assertEquals("MINOR", view.drift());
    }

    @Test
    void getApplication_currentApp_projectsNotOutdatedWithNoneDrift() {
        stubApplications();

        ApplicationView view = sut.get_application("gitea");

        assertEquals("gitea", view.name());
        assertFalse(view.outdated(), "up-to-date app must not be outdated");
        assertEquals("NONE", view.drift());
    }

    @Test
    void getApplication_unknownName_returnsNotFound() {
        stubApplications();

        ApplicationView view = sut.get_application("does-not-exist");

        assertNull(view, "unknown application name must yield a not-found (null) result");
    }

    // --- trigger_scrape: thin pass-through of the use case's ScrapeStatus ---

    @Test
    void triggerScrape_scraped_passesOutcomeAndCountsAndBudgetStraightThrough() {
        ScrapeStatus scraped = ScrapeStatus.scraped(3, 0, 9, 60);
        when(applicationVersionPort.triggerScrape()).thenReturn(scraped);

        ScrapeStatus result = sut.trigger_scrape();

        assertEquals(Outcome.SCRAPED, result.outcome());
        assertEquals(3, result.appsAttempted());
        assertEquals(3, result.appsSucceeded());
        assertEquals(0, result.appsFailed());
        assertEquals(9, result.triggersRemaining());
        assertEquals(60, result.windowResetsInSeconds());
        assertEquals(0, result.retryAfterSeconds());
    }

    @Test
    void triggerScrape_rateLimited_passesThroughWithoutThrowing() {
        ScrapeStatus rateLimited = ScrapeStatus.rateLimited(42);
        when(applicationVersionPort.triggerScrape()).thenReturn(rateLimited);

        ScrapeStatus result = sut.trigger_scrape();

        assertEquals(Outcome.RATE_LIMITED, result.outcome(),
                "rate-limited outcome must be conveyed via the field, not an exception");
        assertEquals(42, result.retryAfterSeconds());
    }

    @Test
    void triggerScrape_scraped_passesPerAppTargetResultsStraightThrough() {
        ScrapeStatus scraped = ScrapeStatus.scraped(
                2,
                1,
                9,
                60,
                List.of(
                        TargetResult.success("argo-cd", Side.BOTH),
                        TargetResult.failure("grafana", Side.BOTH, "github down")));
        when(applicationVersionPort.triggerScrape()).thenReturn(scraped);

        ScrapeStatus result = sut.trigger_scrape();

        assertEquals(2, result.targetResults().size(), "the MCP tool must not drop per-app results");
        assertEquals("argo-cd", result.targetResults().get(0).name());
        assertTrue(result.targetResults().get(0).succeeded());
        assertEquals("grafana", result.targetResults().get(1).name());
        assertFalse(result.targetResults().get(1).succeeded());
        assertEquals("github down", result.targetResults().get(1).reason());
    }

    @Test
    void triggerScrape_delegatesToPortExactlyOnce() {
        when(applicationVersionPort.triggerScrape()).thenReturn(ScrapeStatus.scraped(1, 0));

        sut.trigger_scrape();

        verify(applicationVersionPort, times(1)).triggerScrape();
    }

    // --- scrape_applications: maps (name, side) args into ScrapeTargets and delegates ---

    @Test
    void scrapeApplications_singleTarget_buildsScrapeTargetWithParsedSide() {
        ScrapeStatus scraped = ScrapeStatus.scraped(List.of(TargetResult.success("argo-cd", Side.CURRENT)), 9, 60);
        when(applicationVersionPort.targetedScrape(any())).thenReturn(scraped);

        sut.scrape_applications(List.of(new ScrapeTargetArg("argo-cd", Side.CURRENT)));

        verify(applicationVersionPort).targetedScrape(List.of(new ScrapeTarget("argo-cd", Side.CURRENT)));
    }

    @Test
    void scrapeApplications_mixedSides_buildsOneScrapeTargetPerArgInOrder() {
        ScrapeStatus scraped = ScrapeStatus.scraped(
                List.of(
                        TargetResult.success("argo-cd", Side.CURRENT),
                        TargetResult.success("git-tea", Side.LATEST)),
                9,
                60);
        when(applicationVersionPort.targetedScrape(any())).thenReturn(scraped);

        sut.scrape_applications(List.of(
                new ScrapeTargetArg("argo-cd", Side.CURRENT),
                new ScrapeTargetArg("git-tea", Side.LATEST)));

        verify(applicationVersionPort).targetedScrape(List.of(
                new ScrapeTarget("argo-cd", Side.CURRENT),
                new ScrapeTarget("git-tea", Side.LATEST)));
    }

    @Test
    void scrapeApplications_bothSide_parsesToSideBoth() {
        ScrapeStatus scraped = ScrapeStatus.scraped(List.of(TargetResult.success("grafana", Side.BOTH)), 9, 60);
        when(applicationVersionPort.targetedScrape(any())).thenReturn(scraped);

        sut.scrape_applications(List.of(new ScrapeTargetArg("grafana", Side.BOTH)));

        verify(applicationVersionPort).targetedScrape(List.of(new ScrapeTarget("grafana", Side.BOTH)));
    }

    @Test
    void scrapeApplications_returnsPortScrapeStatusStraightThrough() {
        ScrapeStatus scraped = ScrapeStatus.scraped(
                List.of(
                        TargetResult.success("argo-cd", Side.CURRENT),
                        TargetResult.failure("git-tea", Side.LATEST, "github down")),
                7,
                42);
        when(applicationVersionPort.targetedScrape(any())).thenReturn(scraped);

        ScrapeStatus result = sut.scrape_applications(List.of(
                new ScrapeTargetArg("argo-cd", Side.CURRENT),
                new ScrapeTargetArg("git-tea", Side.LATEST)));

        assertEquals(Outcome.SCRAPED, result.outcome());
        assertEquals(2, result.targetResults().size(), "per-target results must not be dropped");
        assertEquals("argo-cd", result.targetResults().get(0).name());
        assertTrue(result.targetResults().get(0).succeeded());
        assertEquals("git-tea", result.targetResults().get(1).name());
        assertFalse(result.targetResults().get(1).succeeded());
        assertEquals("github down", result.targetResults().get(1).reason());
        assertEquals(7, result.triggersRemaining());
        assertEquals(42, result.windowResetsInSeconds());
    }

    @Test
    void scrapeApplications_rateLimited_passesThroughWithoutThrowing() {
        when(applicationVersionPort.targetedScrape(any())).thenReturn(ScrapeStatus.rateLimited(15));

        ScrapeStatus result = sut.scrape_applications(List.of(new ScrapeTargetArg("argo-cd", Side.CURRENT)));

        assertEquals(Outcome.RATE_LIMITED, result.outcome(),
                "rate-limited outcome must be conveyed via the field, not an exception");
        assertEquals(15, result.retryAfterSeconds());
    }

    @Test
    void scrapeApplications_delegatesToPortExactlyOnce() {
        when(applicationVersionPort.targetedScrape(any())).thenReturn(ScrapeStatus.scraped(List.of(), 9, 60));

        sut.scrape_applications(List.of(new ScrapeTargetArg("argo-cd", Side.CURRENT)));

        verify(applicationVersionPort, times(1)).targetedScrape(any());
    }

    // --- scrape_applications: tool description must document telemetry-only behaviour ---

    @Test
    void scrapeApplicationsToolDescription_mentionsTelemetryOnlyFollowUpRead() throws NoSuchMethodException {
        Method method = ApplicationMcpTools.class.getMethod("scrape_applications", List.class);
        io.quarkiverse.mcp.server.Tool annotation = method.getAnnotation(io.quarkiverse.mcp.server.Tool.class);
        String description = annotation.description().toLowerCase();

        assertTrue(description.contains("get_application") || description.contains("list_outdated_applications"),
                "description must point the agent to a follow-up read: " + description);
        assertTrue(description.contains("telemetry"),
                "description must say the tool is telemetry-only (no version data inline): " + description);
        assertTrue(description.contains("rate") || description.contains("budget"),
                "description must call out the targeted rate limit: " + description);
        assertTrue(description.contains("separate") || description.contains("distinct"),
                "description must say the targeted budget is separate from trigger_scrape's: " + description);
    }
}
