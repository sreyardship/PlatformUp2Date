package org.yardship.unit.adapters.in.mcp;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.in.mcp.ApplicationMcpTools;
import org.yardship.adapters.in.mcp.ApplicationView;
import org.yardship.core.domain.primitives.Version;
import org.yardship.core.domain.primitives.VersionApplication;
import org.yardship.core.ports.in.ApplicationVersionPort;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
}
