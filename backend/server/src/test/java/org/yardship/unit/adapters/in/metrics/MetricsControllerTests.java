package org.yardship.unit.adapters.in.metrics;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.in.metrics.MetricsController;
import org.yardship.adapters.out.versionsource.configerror.ConfigError;
import org.yardship.adapters.out.versionsource.configerror.ConfigErrorScope;
import org.yardship.adapters.out.versionsource.configerror.ConfigErrors;
import org.yardship.core.domain.primitives.SemverVersion;
import org.yardship.core.domain.primitives.SideObservation;
import org.yardship.core.domain.primitives.VersionApplication;

import java.time.Instant;
import java.util.Optional;
import org.yardship.core.ports.in.ApplicationVersionPort;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@QuarkusTest
public class MetricsControllerTests {

    @InjectMock
    private ApplicationVersionPort applicationVersionPort;

    @InjectMock
    private ConfigErrors configErrors;

    @Inject
    private MetricsController sut;

    private static final Instant NOW = Instant.parse("2026-07-01T10:00:00Z");

    @Test
    void getMetrics_delegatesPortApplicationsToRenderer() {
        VersionApplication majorBehind = new VersionApplication("argo-cd",
                SideObservation.resolved(new SemverVersion("1.1.1"), NOW),
                SideObservation.resolved(new SemverVersion("2.2.2"), NOW));
        VersionApplication current = new VersionApplication("grafana",
                SideObservation.resolved(new SemverVersion("2.0.0"), NOW),
                SideObservation.resolved(new SemverVersion("2.0.0"), NOW));
        when(applicationVersionPort.getApplications())
                .thenReturn(List.of(majorBehind, current));

        String output = sut.getMetrics();

        assertTrue(output.contains("pu2d_version_drift_level{app=\"argo-cd\"} 3"),
                "expected argo-cd major drift in: " + output);
        assertTrue(output.contains("pu2d_version_drift_level{app=\"grafana\"} 0"),
                "expected grafana current drift in: " + output);
    }

    @Test
    void getMetrics_includesInfoFamilyWithAllApps_resolvedAndNeverAttempted() {
        VersionApplication resolved = new VersionApplication("grafana",
                SideObservation.resolved(new SemverVersion("11.0.0"), NOW),
                SideObservation.resolved(new SemverVersion("11.1.0"), NOW));
        VersionApplication pending  = new VersionApplication("pending-app",
                new SideObservation(Optional.empty(), Optional.empty(), Optional.empty()),
                new SideObservation(Optional.empty(), Optional.empty(), Optional.empty()));
        when(applicationVersionPort.getApplications())
                .thenReturn(List.of(resolved, pending));

        String output = sut.getMetrics();

        assertTrue(output.contains("# HELP pu2d_application_info "),
                "expected info HELP line in: " + output);
        assertTrue(output.contains("# TYPE pu2d_application_info gauge"),
                "expected info TYPE line in: " + output);
        assertTrue(output.contains(
                "pu2d_application_info{app=\"grafana\",current=\"11.0.0\",latest=\"11.1.0\"} 1"),
                "expected grafana info sample in: " + output);
        assertTrue(output.contains(
                "pu2d_application_info{app=\"pending-app\",current=\"\",latest=\"\"} 1"),
                "expected pending-app info sample with empty labels in: " + output);
    }

    @Test
    void getMetrics_passesTheUnnamedAppCountFromConfigErrors_ratherThanAHardcodedZero() {
        // The renderer's own tests pin how the family is rendered; this pins the wiring, so a
        // controller that quietly passed a literal 0 could not pass unnoticed (issue 02).
        when(applicationVersionPort.getApplications()).thenReturn(List.of());
        when(configErrors.unnamedAppCount()).thenReturn(4);

        String output = sut.getMetrics();

        assertTrue(output.contains("pu2d_config_unnamed_apps 4"),
                "expected the controller to pass ConfigErrors' count through to the renderer; was: " + output);
    }

    @Test
    void getMetrics_passesTheRecordedConfigErrors_ratherThanAnEmptyList() {
        // Slice 06's review caught exactly this class of gap: deleting an argument left the
        // suite green. Pin the wiring so the controller cannot quietly pass List.of() instead of
        // the real recorded errors from ConfigErrors#all().
        when(applicationVersionPort.getApplications()).thenReturn(List.of());
        when(configErrors.all()).thenReturn(
                List.of(new ConfigError("argo-cd", ConfigErrorScope.CURRENT, "boom")));

        String output = sut.getMetrics();

        assertTrue(output.contains("pu2d_config_error{application=\"argo-cd\",scope=\"CURRENT\"} 1"),
                "expected the controller to pass ConfigErrors#all() through to the renderer; was: " + output);
    }
}
