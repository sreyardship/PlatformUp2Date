package org.yardship.integration.adapters.in.mcp;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.ChangelogTemplates;
import org.yardship.core.ports.in.ApplicationVersionPort;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;

/**
 * The "web on / mcp off" corner of the four on/off combinations (issue 02, docs/adr/0026,
 * docs/adr/0028) — the mirror image of {@link McpOidcNonMcpSurfacesIT} (mcp on / web off). Runs
 * under {@link WebOnlyAuthTestProfile}, which sets {@code WEB_OIDC_ROLE} but leaves
 * {@code MCP_OIDC_ROLE} unset: {@code /api/v1} must be gated (proven by {@link WebAuthEnforcedIT}
 * -equivalent behavior — this class only pins that {@code /api/mcp} stays OPEN, plus the always-
 * open surfaces), while {@code /api/mcp} must be reachable by an unauthenticated caller exactly
 * as it is when auth is fully off.
 */
@QuarkusTest
@TestProfile(WebOnlyAuthTestProfile.class)
class WebOnlyOpenMcpSurfaceIT {

    @InjectMock
    ApplicationVersionPort applicationVersionPort;

    @InjectMock
    ChangelogTemplates changelogTemplates;

    @Test
    void mcpEndpoint_withNoCredentials_isReachable_whenOnlyWebAuthIsOn() {
        when(applicationVersionPort.getApplications()).thenReturn(List.of());

        McpAssured.newStreamableClient()
                .setMcpPath("/api/mcp")
                .build()
                .connect()
                .when()
                .toolsList(page -> {
                })
                .thenAssertResults();
    }

    @Test
    void versionEndpoint_withNoCredentials_isRejectedWithUnauthorized_whenWebAuthIsOn() {
        // The counterpart proof (in the same combination) that /api/v1 IS gated here — full
        // 200/403/401 enforcement coverage lives in the web-only-token cases of WebAuthEnforcedIT
        // and CrossSurfaceIsolationIT (which both run under BothSurfacesAuthTestProfile); this
        // class only needs the single no-credentials 401 to prove the gate is active in the
        // web-only-profile world too, distinct from the "web off" world McpOidcNonMcpSurfacesIT
        // pins.
        given()
                .when()
                .get("/api/v1/version")
                .then()
                .statusCode(401);
    }

    @Test
    void health_withNoCredentials_isReachable_whenOnlyWebAuthIsOn() {
        given()
                .when()
                .get("/q/health")
                .then()
                .statusCode(not(401));
    }

    @Test
    void metrics_withNoCredentials_isReachable_whenOnlyWebAuthIsOn() {
        given()
                .when()
                .get("/metrics")
                .then()
                .statusCode(not(401));
    }
}
