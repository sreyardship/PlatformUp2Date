package org.yardship.integration.adapters.in.mcp;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
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
import static org.mockito.Mockito.when;

/**
 * Isolation guard for docs/adr/0026 / issue 03: turning MCP endpoint OAuth ON must change
 * NOTHING for the REST API ({@code /api/v1/*}) or health ({@code /q/health}) — their protection
 * story (edge proxy / private network) is out of scope for this switch, and remains so.
 *
 * <p>Runs under the same auth-on {@link SurfaceAuthTestProfile} as {@link McpOidcAuthEnforcedIT}
 * (real Keycloak Dev Services tenant, OIDC_ISSUER/OIDC_AUDIENCE set) — the exact condition under
 * which Quarkus's default proactive authentication could otherwise 401 a permit-all path just
 * because the request carries an {@code Authorization} header the configured tenant cannot
 * validate. A browser extension or misconfigured client attaching a stale/foreign bearer token
 * must not be able to knock the public board's REST API or health endpoint over with a 401.
 *
 * <p>{@link ApplicationVersionPort} and {@link ChangelogTemplates} are mocked exactly as in
 * {@link org.yardship.integration.adapters.in.http.VersionControllerIT}, so a 200 here reflects
 * the controller actually serving the request, not an unrelated 500.
 */
@QuarkusTest
@TestProfile(SurfaceAuthTestProfile.class)
class McpOidcNonMcpSurfacesIT {

    @InjectMock
    ApplicationVersionPort applicationVersionPort;

    @InjectMock
    ChangelogTemplates changelogTemplates;

    private void stubOneApp() {
        Instant readAt = Instant.parse("2026-07-01T10:00:00Z");
        when(applicationVersionPort.getApplications()).thenReturn(List.of(
                new VersionApplication("grafana",
                        SideObservation.resolved(new SemverVersion("2.2.0"), readAt),
                        SideObservation.resolved(new SemverVersion("2.2.1"), readAt))));
    }

    @Test
    void restApi_withNoCredentials_isServedAnonymously_whenMcpAuthIsOn() {
        stubOneApp();

        given()
                .when()
                .get("/api/v1/version")
                .then()
                .statusCode(200);
    }

    @Test
    void restApi_withGarbageBearerToken_isStillServedAnonymously_notRejectedWithUnauthorized() {
        // The trap this test pins: a stale/foreign/garbage bearer token on a permit-all path must
        // not trigger Quarkus's proactive-auth 401, even though an OIDC bearer tenant is enabled
        // (for /api/mcp*) elsewhere in this same running application.
        stubOneApp();

        given()
                .header("Authorization", "Bearer xyz")
                .when()
                .get("/api/v1/version")
                .then()
                .statusCode(200);
    }

    @Test
    void health_withNoCredentials_isReachable_whenMcpAuthIsOn() {
        given()
                .when()
                .get("/q/health")
                .then()
                .statusCode(not(401));
    }

    @Test
    void health_withGarbageBearerToken_isStillReachable_notRejectedWithUnauthorized() {
        given()
                .header("Authorization", "Bearer xyz")
                .when()
                .get("/q/health")
                .then()
                .statusCode(not(401));
    }
}
