package org.yardship.unit.adapters.in.metrics;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.in.metrics.MetricsController;
import org.yardship.core.domain.primitives.SemverVersion;
import org.yardship.core.domain.primitives.SideObservation;
import org.yardship.core.domain.primitives.VersionValue;
import org.yardship.core.domain.primitives.VersionApplication;

import java.time.Instant;
import org.yardship.core.ports.in.ApplicationVersionPort;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@QuarkusTest
public class MetricsControllerTests {

    @InjectMock
    private ApplicationVersionPort applicationVersionPort;

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
}
