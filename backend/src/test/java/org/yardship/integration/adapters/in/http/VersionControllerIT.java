package org.yardship.integration.adapters.in.http;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.scrapestate.ScrapeStateUnavailableException;
import org.yardship.core.domain.primitives.Version;
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
 * <ul>
 *   <li>the JSON shape (a map of name -> {current, latest}) is unchanged from today;</li>
 *   <li>fail-closed: when the snapshot source is unavailable (port throws), the endpoint
 *       returns 503 rather than a 200 with an empty/partial body.</li>
 * </ul>
 */
@QuarkusTest
class VersionControllerIT {

    @InjectMock
    ApplicationVersionPort applicationVersionPort;

    @Test
    void getVersion_returnsSnapshotShape() {
        when(applicationVersionPort.getApplications()).thenReturn(List.of(
                new VersionApplication("argo-cd", new Version("1.0.0"), new Version("2.0.0"))));

        given()
                .when()
                .get("/api/v1/version")
                .then()
                .statusCode(200)
                .body("'argo-cd'.current", equalTo("1.0.0"))
                .body("'argo-cd'.latest", equalTo("2.0.0"));
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
