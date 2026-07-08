package org.yardship.unit.adapters.in.mcp;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.in.mcp.ApplicationMcpTools;
import org.yardship.adapters.in.mcp.ApplicationMcpTools.ScrapeTargetArg;
import org.yardship.adapters.in.mcp.ApplicationView;
import org.yardship.adapters.out.versionsource.ChangelogTemplates;
import org.yardship.core.domain.primitives.ChangelogTemplate;
import org.yardship.core.domain.primitives.ScrapeTarget;
import org.yardship.core.domain.primitives.Side;
import org.yardship.core.domain.primitives.SideObservation;
import org.yardship.core.domain.primitives.TargetResult;
import org.yardship.core.domain.primitives.SemverVersion;
import org.yardship.core.domain.primitives.VersionScheme;
import org.yardship.core.domain.primitives.VersionValue;
import org.yardship.core.domain.primitives.VersionApplication;

import java.time.Instant;
import org.yardship.core.ports.in.ApplicationVersionPort;
import org.yardship.core.ports.in.Outcome;
import org.yardship.core.ports.in.ScrapeStatus;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    // Issue 03 (changelog link on the MCP surface, ADR-0021): the shared per-app template lookup
    // built in slice 01 (org.yardship.adapters.out.versionsource.ChangelogTemplates). Mocked here
    // so each test controls exactly which apps carry a template, mirroring VersionControllerIT's
    // approach for the REST sibling.
    @InjectMock
    private ChangelogTemplates changelogTemplates;

    @Inject
    private ApplicationMcpTools sut;

    private static final Instant NOW = Instant.parse("2026-07-01T10:00:00Z");

    private final VersionApplication majorBehind = new VersionApplication("argo-cd",
            SideObservation.resolved(new SemverVersion("1.1.1"), NOW),
            SideObservation.resolved(new SemverVersion("2.2.2"), NOW));
    private final VersionApplication minorBehind = new VersionApplication("grafana",
            SideObservation.resolved(new SemverVersion("2.1.0"), NOW),
            SideObservation.resolved(new SemverVersion("2.2.0"), NOW));
    private final VersionApplication current = new VersionApplication("gitea",
            SideObservation.resolved(new SemverVersion("2.0.0"), NOW),
            SideObservation.resolved(new SemverVersion("2.0.0"), NOW));

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

        List<ApplicationView> result = sut.list_outdated_applications(VersionValue.Diff.MAJOR);

        assertEquals(1, result.size());
        assertEquals("argo-cd", result.getFirst().name());
    }

    @Test
    void listOutdatedApplications_projectsViewFieldsCorrectly() {
        stubApplications();

        ApplicationView view = sut.list_outdated_applications(VersionValue.Diff.MAJOR).getFirst();

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

    // === Issue 05: expanded ApplicationView (resolution + per-side freshness instants) ============

    // --- Helpers for the four-state SideObservation matrix ---

    /** Pending: never attempted (all-empty). failedRefresh() → false. */
    private SideObservation pending() {
        return new SideObservation(Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** Failed-refresh: had a prior value but the newest attempt failed. failedRefresh() → true. */
    private SideObservation failedRefreshAfterSuccess(String version) {
        Instant successAt = NOW.minusSeconds(60);
        Instant failureAt = NOW; // failure strictly newer
        return new SideObservation(
                Optional.of(new SemverVersion(version)),
                Optional.of(successAt),
                Optional.of(failureAt));
    }

    /** Never-succeeded-failed: no value, no prior success, but has a failure stamp. failedRefresh() → true. */
    private SideObservation neverSucceededFailed() {
        return new SideObservation(Optional.empty(), Optional.empty(), Optional.of(NOW));
    }

    // --- get_application: freshness fields in the expanded ApplicationView ---

    @Test
    void getApplication_resolvedApp_exposesResolutionAndReadAtInstants() {
        VersionApplication resolvedApp = new VersionApplication("prometheus",
                SideObservation.resolved(new SemverVersion("2.50.0"), NOW),
                SideObservation.resolved(new SemverVersion("2.51.0"), NOW));
        when(applicationVersionPort.getApplications()).thenReturn(List.of(resolvedApp));

        ApplicationView view = sut.get_application("prometheus");

        assertNotNull(view, "known app must not return null");
        assertEquals("Resolved", view.resolution(), "a resolved app must carry resolution=Resolved");
        // per-side read instants must be populated
        assertEquals(NOW, view.currentReadAt(),
                "currentReadAt must equal the side's lastSuccessAt for a resolved app");
        assertEquals(NOW, view.latestReadAt(),
                "latestReadAt must equal the side's lastSuccessAt for a resolved app");
        // no failures → failedAt instants must be null
        assertNull(view.currentFailedAt(),
                "currentFailedAt must be null when the current side has not failed");
        assertNull(view.latestFailedAt(),
                "latestFailedAt must be null when the latest side has not failed");
    }

    @Test
    void getApplication_failedRefreshApp_exposesFailedAtAndReadAt() {
        // Current side: had a value then the newest attempt failed; latest: still resolved.
        Instant successAt = NOW.minusSeconds(60);
        Instant failureAt = NOW;
        SideObservation failedCurrent = new SideObservation(
                Optional.of(new SemverVersion("1.0.0")),
                Optional.of(successAt),
                Optional.of(failureAt));
        VersionApplication failedRefreshApp = new VersionApplication("vault",
                failedCurrent,
                SideObservation.resolved(new SemverVersion("1.1.0"), NOW));
        when(applicationVersionPort.getApplications()).thenReturn(List.of(failedRefreshApp));

        ApplicationView view = sut.get_application("vault");

        assertNotNull(view);
        // current side: has prior value → readAt populated; newest attempt failed → failedAt populated
        assertEquals(successAt, view.currentReadAt(),
                "currentReadAt must be the last success instant even when a later failure occurred");
        assertEquals(failureAt, view.currentFailedAt(),
                "currentFailedAt must carry the failure instant when failedRefresh() is true");
        // latest side is still clean
        assertEquals(NOW, view.latestReadAt());
        assertNull(view.latestFailedAt(),
                "latestFailedAt must be null when the latest side has not failed");
    }

    @Test
    void getApplication_unresolvedPendingApp_exposesUnresolvedResolutionAndNullInstants() {
        // Both sides: pending (never attempted). Unresolved app — no version, no instants.
        VersionApplication unresolvedApp = new VersionApplication("loki",
                pending(), pending());
        when(applicationVersionPort.getApplications()).thenReturn(List.of(unresolvedApp));

        ApplicationView view = sut.get_application("loki");

        assertNotNull(view);
        assertEquals("Unresolved", view.resolution(),
                "an app with value-less sides must carry resolution=Unresolved");
        assertNull(view.current(), "pending side must have null version");
        assertNull(view.latest(), "pending side must have null version");
        assertNull(view.currentReadAt(), "pending side has never been read → currentReadAt must be null");
        assertNull(view.latestReadAt(), "pending side has never been read → latestReadAt must be null");
        assertNull(view.currentFailedAt(), "pending side has no failure → currentFailedAt must be null");
        assertNull(view.latestFailedAt(), "pending side has no failure → latestFailedAt must be null");
        assertFalse(view.outdated(), "Unresolved app must not be marked outdated");
        assertNull(view.drift(), "Unresolved app must have null drift (never 'NONE')");
    }

    @Test
    void getApplication_neverSucceededFailedApp_exposesNullVersionAndFailedAt() {
        // Latest side: never succeeded but has a failure stamp. Null version, null readAt, failedAt set.
        VersionApplication neverSucceededApp = new VersionApplication("tempo",
                SideObservation.resolved(new SemverVersion("2.0.0"), NOW),
                neverSucceededFailed());
        when(applicationVersionPort.getApplications()).thenReturn(List.of(neverSucceededApp));

        ApplicationView view = sut.get_application("tempo");

        assertNotNull(view);
        assertEquals("Unresolved", view.resolution(),
                "an app with a value-less latest side must be Unresolved");
        assertNull(view.latest(), "never-succeeded-failed side must have null version");
        assertNull(view.latestReadAt(), "never-succeeded-failed side has no success → latestReadAt must be null");
        assertEquals(NOW, view.latestFailedAt(),
                "latestFailedAt must carry the failure instant for a never-succeeded-failed side");
    }

    @Test
    void applicationView_hasNoReasonField() {
        // The failure REASON must NOT be surfaced to the MCP layer (it stays in logs by design).
        // Verify no record component named "reason" exists on ApplicationView.
        boolean hasReasonField = Arrays.stream(ApplicationView.class.getRecordComponents())
                .anyMatch(rc -> rc.getName().equalsIgnoreCase("reason"));
        assertFalse(hasReasonField,
                "ApplicationView must not carry a failure reason field — the reason stays in logs by design");
    }

    // --- list_applications_with_failed_scrapes: four-state matrix ---

    @Test
    void listApplicationsWithFailedScrapes_freshSuccessApp_isExcluded() {
        // Fresh success: both sides resolved, no failures.
        when(applicationVersionPort.getApplications()).thenReturn(List.of(current));

        List<ApplicationView> result = sut.list_applications_with_failed_scrapes();

        assertTrue(result.isEmpty(),
                "an app with both sides freshly resolved must be excluded from failed-scrapes list");
    }

    @Test
    void listApplicationsWithFailedScrapes_pendingApp_isExcluded() {
        // Pending: never attempted, no failures. Calling a scrape that never ran "failed" is a lie.
        VersionApplication pendingApp = new VersionApplication("pending-app", pending(), pending());
        when(applicationVersionPort.getApplications()).thenReturn(List.of(pendingApp));

        List<ApplicationView> result = sut.list_applications_with_failed_scrapes();

        assertTrue(result.isEmpty(),
                "a pending app (never attempted) must be excluded — it has no failed scrape, just no scrape");
    }

    @Test
    void listApplicationsWithFailedScrapes_failedRefreshApp_isIncluded() {
        // Failed-refresh: current side had a value then the newest attempt failed.
        VersionApplication failedApp = new VersionApplication("vault",
                failedRefreshAfterSuccess("1.0.0"),
                SideObservation.resolved(new SemverVersion("1.1.0"), NOW));
        when(applicationVersionPort.getApplications()).thenReturn(List.of(failedApp));

        List<ApplicationView> result = sut.list_applications_with_failed_scrapes();

        assertEquals(1, result.size(), "failed-refresh app must be included");
        assertEquals("vault", result.getFirst().name());
    }

    @Test
    void listApplicationsWithFailedScrapes_neverSucceededFailedApp_isIncluded() {
        // Never-succeeded-failed: latest side never got a value but has a failure stamp.
        VersionApplication neverSucceededApp = new VersionApplication("tempo",
                SideObservation.resolved(new SemverVersion("2.0.0"), NOW),
                neverSucceededFailed());
        when(applicationVersionPort.getApplications()).thenReturn(List.of(neverSucceededApp));

        List<ApplicationView> result = sut.list_applications_with_failed_scrapes();

        assertEquals(1, result.size(), "never-succeeded-failed app must be included");
        assertEquals("tempo", result.getFirst().name());
    }

    @Test
    void listApplicationsWithFailedScrapes_mixedFleet_returnsOnlyFailedOnes() {
        // Fleet with all four states: only the two failed ones must appear.
        VersionApplication freshApp    = new VersionApplication("prometheus",
                SideObservation.resolved(new SemverVersion("2.50.0"), NOW),
                SideObservation.resolved(new SemverVersion("2.51.0"), NOW));
        VersionApplication pendingApp  = new VersionApplication("pending-app", pending(), pending());
        VersionApplication failedRefreshApp = new VersionApplication("vault",
                failedRefreshAfterSuccess("1.0.0"),
                SideObservation.resolved(new SemverVersion("1.1.0"), NOW));
        VersionApplication neverSucceededApp = new VersionApplication("tempo",
                SideObservation.resolved(new SemverVersion("2.0.0"), NOW),
                neverSucceededFailed());

        when(applicationVersionPort.getApplications())
                .thenReturn(List.of(freshApp, pendingApp, failedRefreshApp, neverSucceededApp));

        List<ApplicationView> result = sut.list_applications_with_failed_scrapes();

        assertEquals(2, result.size(), "only the failed-scrape apps must be returned");
        List<String> names = result.stream().map(ApplicationView::name).toList();
        assertTrue(names.contains("vault"),   "failed-refresh app must be in the result");
        assertTrue(names.contains("tempo"),   "never-succeeded-failed app must be in the result");
        assertFalse(names.contains("prometheus"), "fresh app must be excluded");
        assertFalse(names.contains("pending-app"), "pending app must be excluded");
    }

    // --- list_outdated_applications: regression — unchanged behaviour with issue 05 ---

    @Test
    void listOutdatedApplications_regression_excludesUnresolvedApps() {
        // Unresolved apps (pending or failed-refresh with no value on a side) must never appear
        // in the outdated list — they cannot have drift.
        VersionApplication pendingApp = new VersionApplication("pending-app", pending(), pending());
        VersionApplication failedApp  = new VersionApplication("vault",
                failedRefreshAfterSuccess("1.0.0"),
                neverSucceededFailed());
        when(applicationVersionPort.getApplications()).thenReturn(List.of(majorBehind, pendingApp, failedApp));

        List<ApplicationView> result = sut.list_outdated_applications(null);

        assertEquals(1, result.size(), "only the resolved drifting app must appear");
        assertEquals("argo-cd", result.getFirst().name());
    }

    @Test
    void listOutdatedApplications_regression_excludesUnresolvedEvenWhenBothSidesFailed() {
        // A both-sides-failed app is Unresolved — must be absent from the outdated list.
        VersionApplication bothFailed = new VersionApplication("both-failed",
                neverSucceededFailed(), neverSucceededFailed());
        when(applicationVersionPort.getApplications()).thenReturn(List.of(bothFailed));

        List<ApplicationView> result = sut.list_outdated_applications(null);

        assertTrue(result.isEmpty(), "Unresolved app with failed sides must not appear in outdated list");
    }

    // --- list_applications_with_failed_scrapes: tool description must frame failure on the SCRAPE ---

    @Test
    void listApplicationsWithFailedScrapesToolDescription_attributesFailureToScrapeNotApplication()
            throws NoSuchMethodException {
        Method method = ApplicationMcpTools.class.getMethod("list_applications_with_failed_scrapes");
        io.quarkiverse.mcp.server.Tool annotation = method.getAnnotation(io.quarkiverse.mcp.server.Tool.class);
        String description = annotation.description().toLowerCase();

        assertTrue(description.contains("scrape") || description.contains("read"),
                "description must frame failure as a scrape/read failure, not an app problem: " + description);
        assertFalse(description.contains("unhealthy") || description.contains("down"),
                "description must NOT call the application itself unhealthy or down: " + description);
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

    // === Issue 03: changelogUrl on the MCP surface (ADR-0021) ======================================
    //
    // ApplicationView must carry the same nullable changelogUrl the REST payload carries, resolved
    // from the shared ChangelogTemplates bean (slice 01) — the exact same semantics as
    // ApplicationStatus.from: null when no template is configured, null when the latest side has no
    // known version, otherwise the template resolved against the displayed latest version.

    @Test
    void getApplication_exposesChangelogUrl_whenTemplateConfiguredAndLatestResolved() {
        VersionApplication app = new VersionApplication("argo-cd",
                SideObservation.resolved(new SemverVersion("3.0.4"), NOW),
                SideObservation.resolved(new SemverVersion("3.0.5"), NOW));
        when(applicationVersionPort.getApplications()).thenReturn(List.of(app));
        when(changelogTemplates.forApp("argo-cd")).thenReturn(Optional.of(
                new ChangelogTemplate(
                        "https://github.com/argoproj/argo-cd/releases/tag/v{version}",
                        VersionScheme.SEMVER,
                        Optional.empty())));

        ApplicationView view = sut.get_application("argo-cd");

        assertNotNull(view);
        assertEquals("https://github.com/argoproj/argo-cd/releases/tag/v3.0.5", view.changelogUrl(),
                "changelogUrl must be resolved against the displayed latest version");
    }

    @Test
    void getApplication_changelogUrlIsNull_whenNoTemplateConfigured() {
        VersionApplication app = new VersionApplication("untemplated-app",
                SideObservation.resolved(new SemverVersion("1.0.0"), NOW),
                SideObservation.resolved(new SemverVersion("1.1.0"), NOW));
        when(applicationVersionPort.getApplications()).thenReturn(List.of(app));
        // changelogTemplates.forApp(...) is unstubbed → Optional.empty() (Mockito default for this app).

        ApplicationView view = sut.get_application("untemplated-app");

        assertNotNull(view);
        assertNull(view.changelogUrl(), "changelogUrl must be null when no template is configured");
    }

    @Test
    void getApplication_changelogUrlIsNull_whenLatestSideHasNoKnownVersion_evenWithTemplateConfigured() {
        VersionApplication app = new VersionApplication("cold-templated-app",
                SideObservation.resolved(new SemverVersion("1.0.0"), NOW),
                pending());
        when(applicationVersionPort.getApplications()).thenReturn(List.of(app));
        when(changelogTemplates.forApp("cold-templated-app")).thenReturn(Optional.of(
                new ChangelogTemplate(
                        "https://github.com/example/example/releases/tag/v{version}",
                        VersionScheme.SEMVER,
                        Optional.empty())));

        ApplicationView view = sut.get_application("cold-templated-app");

        assertNotNull(view);
        assertNull(view.changelogUrl(),
                "changelogUrl must be null when the latest side has no known version, "
                        + "even with a template configured");
    }
}
