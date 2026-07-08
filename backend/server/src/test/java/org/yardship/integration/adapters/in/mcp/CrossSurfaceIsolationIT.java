package org.yardship.integration.adapters.in.mcp;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.MultiMap;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.ChangelogTemplates;
import org.yardship.core.domain.primitives.SemverVersion;
import org.yardship.core.domain.primitives.SideObservation;
import org.yardship.core.domain.primitives.VersionApplication;
import org.yardship.core.ports.in.ApplicationVersionPort;

import java.time.Instant;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static org.yardship.integration.adapters.in.mcp.SurfaceAuthTestProfile.MCP_ONLY_PASSWORD;
import static org.yardship.integration.adapters.in.mcp.SurfaceAuthTestProfile.MCP_ONLY_USERNAME;
import static org.yardship.integration.adapters.in.mcp.SurfaceAuthTestProfile.RIGHT_AUDIENCE_CLIENT_ID;
import static org.yardship.integration.adapters.in.mcp.SurfaceAuthTestProfile.TEST_CLIENT_SECRET;
import static org.yardship.integration.adapters.in.mcp.SurfaceAuthTestProfile.TEST_PASSWORD;
import static org.yardship.integration.adapters.in.mcp.SurfaceAuthTestProfile.TEST_USERNAME;
import static org.yardship.integration.adapters.in.mcp.SurfaceAuthTestProfile.WEB_ONLY_PASSWORD;
import static org.yardship.integration.adapters.in.mcp.SurfaceAuthTestProfile.WEB_ONLY_USERNAME;
import static org.yardship.integration.adapters.in.mcp.WebAuthEnforcedIT.fetchAccessToken;

/**
 * The headline proof for issue 02 (docs/adr/0026, docs/adr/0028): under
 * {@link BothSurfacesAuthTestProfile} (both {@code MCP_OIDC_ROLE} and {@code WEB_OIDC_ROLE} set,
 * ONE shared issuer+audience), each surface is gated by its OWN role, independent of the other —
 * a token minted for the shared audience is admitted or rejected per-surface based purely on
 * which realm role(s) it carries, never on the audience (which is identical for every token
 * here).
 *
 * <ul>
 *   <li>{@code wanda} (pu2d-web only): 200 on {@code /api/v1}, 403 on {@code /api/mcp};</li>
 *   <li>{@code mona} (pu2d-mcp only): 403 on {@code /api/v1}, 200 on {@code /api/mcp};</li>
 *   <li>{@code alice} (both roles): 200 on both.</li>
 * </ul>
 *
 * <p>The {@code /api/mcp} assertions use {@link McpAssured} exactly as {@link
 * McpOidcAuthEnforcedIT}; the {@code /api/v1} assertions use RestAssured exactly as {@link
 * WebAuthEnforcedIT}, reusing its token-fetch helper.
 */
@QuarkusTest
@TestProfile(BothSurfacesAuthTestProfile.class)
class CrossSurfaceIsolationIT {

    @InjectMock
    ApplicationVersionPort applicationVersionPort;

    @InjectMock
    ChangelogTemplates changelogTemplates;

    private static MultiMap bearerHeader(String token) {
        return MultiMap.caseInsensitiveMultiMap().set("Authorization", "Bearer " + token);
    }

    private void stubOneApp() {
        Instant readAt = Instant.parse("2026-07-01T10:00:00Z");
        when(applicationVersionPort.getApplications()).thenReturn(List.of(
                new VersionApplication("grafana",
                        SideObservation.resolved(new SemverVersion("2.2.0"), readAt),
                        SideObservation.resolved(new SemverVersion("2.2.1"), readAt))));
    }

    @Test
    void webOnlyToken_isAcceptedOnWebSurface() {
        stubOneApp();
        String token = fetchAccessToken(RIGHT_AUDIENCE_CLIENT_ID, TEST_CLIENT_SECRET,
                WEB_ONLY_USERNAME, WEB_ONLY_PASSWORD);

        given()
                .header("Authorization", "Bearer " + token)
                .when()
                .get("/api/v1/version")
                .then()
                .statusCode(200);
    }

    @Test
    void webOnlyToken_isForbiddenOnMcpSurface() {
        String token = fetchAccessToken(RIGHT_AUDIENCE_CLIENT_ID, TEST_CLIENT_SECRET,
                WEB_ONLY_USERNAME, WEB_ONLY_PASSWORD);

        McpAssured.newStreamableClient()
                .setMcpPath("/api/mcp")
                .setAdditionalHeaders(message -> bearerHeader(token))
                .setExpectConnectFailure(failure -> assertEquals(403, failure.statusCode(),
                        "a web-only token (pu2d-web, no pu2d-mcp) must be rejected on /api/mcp "
                                + "with 403 — the two surfaces must isolate even though the "
                                + "token's audience is valid for both"))
                .build()
                .connect();
    }

    @Test
    void mcpOnlyToken_isForbiddenOnWebSurface() {
        String token = fetchAccessToken(RIGHT_AUDIENCE_CLIENT_ID, TEST_CLIENT_SECRET,
                MCP_ONLY_USERNAME, MCP_ONLY_PASSWORD);

        given()
                .header("Authorization", "Bearer " + token)
                .when()
                .get("/api/v1/version")
                .then()
                .statusCode(403);
    }

    @Test
    void mcpOnlyToken_isAcceptedOnMcpSurface() {
        String token = fetchAccessToken(RIGHT_AUDIENCE_CLIENT_ID, TEST_CLIENT_SECRET,
                MCP_ONLY_USERNAME, MCP_ONLY_PASSWORD);

        McpAssured.McpStreamableTestClient client = McpAssured.newStreamableClient()
                .setMcpPath("/api/mcp")
                .setAdditionalHeaders(message -> bearerHeader(token))
                .build()
                .connect();

        client.when()
                .toolsList(page -> {
                })
                .thenAssertResults();
    }

    @Test
    void tokenWithBothRoles_reachesBothSurfaces() {
        stubOneApp();
        String token = fetchAccessToken(RIGHT_AUDIENCE_CLIENT_ID, TEST_CLIENT_SECRET,
                TEST_USERNAME, TEST_PASSWORD);

        given()
                .header("Authorization", "Bearer " + token)
                .when()
                .get("/api/v1/version")
                .then()
                .statusCode(200);

        McpAssured.McpStreamableTestClient client = McpAssured.newStreamableClient()
                .setMcpPath("/api/mcp")
                .setAdditionalHeaders(message -> bearerHeader(token))
                .build()
                .connect();

        client.when()
                .toolsList(page -> {
                })
                .thenAssertResults();
    }

    // --- Open-surface regression: both surfaces gated is the strictest combination ----------

    @Test
    void health_withNoCredentials_isReachable_whenBothSurfacesAreGated() {
        given()
                .when()
                .get("/q/health")
                .then()
                .statusCode(not(401));
    }

    @Test
    void metrics_withNoCredentials_isReachable_whenBothSurfacesAreGated() {
        stubOneApp();

        given()
                .when()
                .get("/metrics")
                .then()
                .statusCode(not(401));
    }
}
