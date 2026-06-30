package org.yardship.integration.adapters.in.http;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.scrapestate.ScrapeStateUnavailableException;
import org.yardship.core.domain.primitives.SemverVersion;
import org.yardship.core.domain.primitives.VersionApplication;
import org.yardship.core.ports.in.ApplicationVersionPort;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.when;

/**
 * HTTP-level tests for {@code GET /api/v1/version}. The inbound port is mocked so these
 * exercise the controller + JAX-RS mapping in isolation from Valkey:
 *
 * <p>One happy-path snapshot-shape test proves the JSON serialization
 * (a map of name → {current, latest, outdated, drift}). Drift-level matrix is owned by
 * {@code SemverVersionTests} (unit). The fail-closed 503 path covers the sole transport
 * behavior: when the snapshot source is unavailable the endpoint must NOT degrade to a 200.
 */
@QuarkusTest
class VersionControllerIT {

    @InjectMock
    ApplicationVersionPort applicationVersionPort;

    @Test
    void getVersion_returnsSnapshotShape_forOutdatedApp() {
        // Use an outdated app so the single kept shape test exercises the non-trivial
        // serialization: outdated == true and a non-NONE drift label string. The full
        // per-drift-level matrix is owned by SemverVersionTests (unit).
        when(applicationVersionPort.getApplications()).thenReturn(List.of(
                new VersionApplication("grafana", new SemverVersion("2.2.0"), new SemverVersion("2.2.1"))));

        given()
                .when()
                .get("/api/v1/version")
                .then()
                .statusCode(200)
                .body("'grafana'.current", equalTo("2.2.0"))
                .body("'grafana'.latest", equalTo("2.2.1"))
                .body("'grafana'.outdated", equalTo(true))
                .body("'grafana'.drift", equalTo("PATCH"));
    }

    @Test
    void getVersion_returns503_whenSnapshotSourceUnavailable() {
        // Fail closed: Valkey unreachable surfaces as the port throwing; the read path
        // must NOT degrade to a 200 with stale/empty data — it returns 503.
        when(applicationVersionPort.getApplications())
                .thenThrow(new ScrapeStateUnavailableException("valkey unreachable", new RuntimeException()));

        given()
                .when()
                .get("/api/v1/version")
                .then()
                .statusCode(503);
    }
}
