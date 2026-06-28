package org.yardship.integration.adapters.in.http;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.scrapestate.ScrapeStateUnavailableException;
import org.yardship.core.domain.primitives.SemverVersion;
import org.yardship.core.domain.primitives.VersionValue;
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
 *   <li>the JSON shape (a map of name -> {current, latest, outdated, drift}) mirrors the
 *       MCP {@code ApplicationView} projection;</li>
 *   <li>fail-closed: when the snapshot source is unavailable (port throws), the endpoint
 *       returns 503 rather than a 200 with an empty/partial body.</li>
 * </ul>
 */
@QuarkusTest
class VersionControllerIT {

    @InjectMock
    ApplicationVersionPort applicationVersionPort;

    @Test
    void getVersion_returnsSnapshotShape_forUpToDateApp() {
        when(applicationVersionPort.getApplications()).thenReturn(List.of(
                new VersionApplication("gitea", new SemverVersion("2.0.0"), new SemverVersion("2.0.0"))));

        given()
                .when()
                .get("/api/v1/version")
                .then()
                .statusCode(200)
                .body("'gitea'.current", equalTo("2.0.0"))
                .body("'gitea'.latest", equalTo("2.0.0"))
                .body("'gitea'.outdated", equalTo(false))
                .body("'gitea'.drift", equalTo("NONE"));
    }

    @Test
    void getVersion_returnsSnapshotShape_forPatchBehindApp() {
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
    void getVersion_returnsSnapshotShape_forMajorBehindApp() {
        when(applicationVersionPort.getApplications()).thenReturn(List.of(
                new VersionApplication("argo-cd", new SemverVersion("1.0.0"), new SemverVersion("2.0.0"))));

        given()
                .when()
                .get("/api/v1/version")
                .then()
                .statusCode(200)
                .body("'argo-cd'.current", equalTo("1.0.0"))
                .body("'argo-cd'.latest", equalTo("2.0.0"))
                .body("'argo-cd'.outdated", equalTo(true))
                .body("'argo-cd'.drift", equalTo("MAJOR"));
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
