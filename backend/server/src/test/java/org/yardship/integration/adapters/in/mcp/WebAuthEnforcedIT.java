package org.yardship.integration.adapters.in.mcp;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.ChangelogTemplates;
import org.yardship.core.domain.primitives.SemverVersion;
import org.yardship.core.domain.primitives.SideObservation;
import org.yardship.core.domain.primitives.VersionApplication;
import org.yardship.core.ports.in.ApplicationVersionPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.mockito.Mockito.when;
import static org.yardship.integration.adapters.in.mcp.SurfaceAuthTestProfile.CLIENT_ROLE_ONLY_PASSWORD;
import static org.yardship.integration.adapters.in.mcp.SurfaceAuthTestProfile.CLIENT_ROLE_ONLY_USERNAME;
import static org.yardship.integration.adapters.in.mcp.SurfaceAuthTestProfile.NO_ROLE_PASSWORD;
import static org.yardship.integration.adapters.in.mcp.SurfaceAuthTestProfile.NO_ROLE_USERNAME;
import static org.yardship.integration.adapters.in.mcp.SurfaceAuthTestProfile.RIGHT_AUDIENCE_CLIENT_ID;
import static org.yardship.integration.adapters.in.mcp.SurfaceAuthTestProfile.SECOND_RIGHT_AUDIENCE_CLIENT_ID;
import static org.yardship.integration.adapters.in.mcp.SurfaceAuthTestProfile.TEST_CLIENT_SECRET;
import static org.yardship.integration.adapters.in.mcp.SurfaceAuthTestProfile.WEB_ONLY_PASSWORD;
import static org.yardship.integration.adapters.in.mcp.SurfaceAuthTestProfile.WEB_ONLY_USERNAME;
import static org.yardship.integration.adapters.in.mcp.SurfaceAuthTestProfile.WRONG_AUDIENCE_CLIENT_ID;

/**
 * Auth-ON integration coverage for the REST API ({@code /api/v1}) surface (issue 02, docs/adr/
 * 0026, docs/adr/0028) — mirrors {@link McpOidcAuthEnforcedIT}'s shape for the web surface. Runs
 * under {@link BothSurfacesAuthTestProfile} (both surfaces gated) against real Keycloak Dev
 * Services; the "web gated / mcp open" corner on its own is pinned separately by
 * {@link WebOnlyOpenMcpSurfaceIT}.
 *
 * <p>Uses {@code wanda} (pu2d-web only) for the happy path and {@code bob} (no roles) for the
 * 403 case, per the realm fixture documented on {@link SurfaceAuthTestProfile}. Token acquisition
 * is a direct password-grant POST via {@link HttpClient}, matching {@link McpOidcAuthEnforcedIT}'s
 * documented deviation from {@code OidcTestClient}.
 */
@QuarkusTest
@TestProfile(BothSurfacesAuthTestProfile.class)
class WebAuthEnforcedIT {

    @InjectMock
    ApplicationVersionPort applicationVersionPort;

    @InjectMock
    ChangelogTemplates changelogTemplates;

    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private static final Pattern ACCESS_TOKEN_PATTERN = Pattern.compile("\"access_token\":\"([^\"]+)\"");

    static String fetchAccessToken(String clientId, String clientSecret, String username, String password) {
        String issuer = ConfigProvider.getConfig().getValue("quarkus.oidc.auth-server-url", String.class);
        String tokenUrl = issuer + "/protocol/openid-connect/token";
        String form = "grant_type=password"
                + "&username=" + username
                + "&password=" + password
                + "&client_id=" + clientId
                + "&client_secret=" + clientSecret;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(tokenUrl))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            Matcher matcher = ACCESS_TOKEN_PATTERN.matcher(response.body());
            if (response.statusCode() != 200 || !matcher.find()) {
                throw new IllegalStateException(
                        "Token request to " + tokenUrl + " for client " + clientId
                                + " failed: status=" + response.statusCode() + " body=" + response.body());
            }
            return matcher.group(1);
        } catch (java.io.IOException | InterruptedException e) {
            throw new IllegalStateException("Token request to " + tokenUrl + " failed", e);
        }
    }

    private void stubOneApp() {
        Instant readAt = Instant.parse("2026-07-01T10:00:00Z");
        when(applicationVersionPort.getApplications()).thenReturn(List.of(
                new VersionApplication("grafana",
                        SideObservation.resolved(new SemverVersion("2.2.0"), readAt),
                        SideObservation.resolved(new SemverVersion("2.2.1"), readAt))));
    }

    @Test
    void versionEndpoint_withPu2dWebToken_isServed() {
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
    void versionEndpoint_withPu2dWebAsClientRoleOfTheMintingClient_isServed() {
        // clara's pu2d-web is a CLIENT role of mcp-client — it arrives in the token as
        // resource_access/mcp-client/roles (azp=mcp-client), not realm_access/roles. Quarkus's
        // default extraction alone cannot see it (no quarkus.oidc.client-id on a bearer-only
        // resource server); AzpClientRolesAugmentor keys the lookup off the token's own azp.
        stubOneApp();
        String token = fetchAccessToken(RIGHT_AUDIENCE_CLIENT_ID, TEST_CLIENT_SECRET,
                CLIENT_ROLE_ONLY_USERNAME, CLIENT_ROLE_ONLY_PASSWORD);

        given()
                .header("Authorization", "Bearer " + token)
                .when()
                .get("/api/v1/version")
                .then()
                .statusCode(200);
    }

    @Test
    void versionEndpoint_withClientRoleOfAForeignClient_isRejectedWithForbidden() {
        // Same user, same (correct) audience — but minted via second-client, so azp=second-client.
        // clara's pu2d-web lives under resource_access/mcp-client, NOT under the token's own azp;
        // the augmentor must only honor the azp's entry, so this stays a 403.
        String token = fetchAccessToken(SECOND_RIGHT_AUDIENCE_CLIENT_ID, TEST_CLIENT_SECRET,
                CLIENT_ROLE_ONLY_USERNAME, CLIENT_ROLE_ONLY_PASSWORD);

        given()
                .header("Authorization", "Bearer " + token)
                .when()
                .get("/api/v1/version")
                .then()
                .statusCode(403);
    }

    @Test
    void versionEndpoint_withValidTokenButWithoutRequiredRole_isRejectedWithForbidden() {
        // bob carries the right audience but neither pu2d-mcp nor pu2d-web.
        String token = fetchAccessToken(RIGHT_AUDIENCE_CLIENT_ID, TEST_CLIENT_SECRET,
                NO_ROLE_USERNAME, NO_ROLE_PASSWORD);

        given()
                .header("Authorization", "Bearer " + token)
                .when()
                .get("/api/v1/version")
                .then()
                .statusCode(403);
    }

    @Test
    void versionEndpoint_withoutBearerToken_isRejectedWithUnauthorized() {
        given()
                .when()
                .get("/api/v1/version")
                .then()
                .statusCode(401);
    }

    @Test
    void versionEndpoint_withTokenForWrongAudience_isRejectedWithUnauthorized() {
        String token = fetchAccessToken(WRONG_AUDIENCE_CLIENT_ID, TEST_CLIENT_SECRET,
                WEB_ONLY_USERNAME, WEB_ONLY_PASSWORD);

        given()
                .header("Authorization", "Bearer " + token)
                .when()
                .get("/api/v1/version")
                .then()
                .statusCode(401);
    }
}
