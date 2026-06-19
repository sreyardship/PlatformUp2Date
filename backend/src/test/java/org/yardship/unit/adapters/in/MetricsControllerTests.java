package org.yardship.unit.adapters.in;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.in.MetricsController;
import org.yardship.core.domain.primitives.Version;
import org.yardship.core.domain.primitives.VersionApplication;
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

    @Test
    void getMetrics_delegatesPortApplicationsToRenderer() {
        VersionApplication majorBehind = new VersionApplication("argo-cd",
                new Version("1.1.1"), new Version("2.2.2"));
        VersionApplication current = new VersionApplication("grafana",
                new Version("2.0.0"), new Version("2.0.0"));
        when(applicationVersionPort.getApplications())
                .thenReturn(List.of(majorBehind, current));

        String output = sut.getMetrics();

        assertTrue(output.contains("pu2d_version_drift_level{app=\"argo-cd\"} 3"),
                "expected argo-cd major drift in: " + output);
        assertTrue(output.contains("pu2d_version_drift_level{app=\"grafana\"} 0"),
                "expected grafana current drift in: " + output);
    }
}
