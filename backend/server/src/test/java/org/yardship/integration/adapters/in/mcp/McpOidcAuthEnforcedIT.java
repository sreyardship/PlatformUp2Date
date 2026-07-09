package org.yardship.integration.adapters.in.mcp;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.MultiMap;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;
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
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;
import static org.yardship.integration.adapters.in.mcp.SurfaceAuthTestProfile.CONFIGURED_AUDIENCE;
import static org.yardship.integration.adapters.in.mcp.SurfaceAuthTestProfile.NO_ROLE_PASSWORD;
import static org.yardship.integration.adapters.in.mcp.SurfaceAuthTestProfile.NO_ROLE_USERNAME;
import static org.yardship.integration.adapters.in.mcp.SurfaceAuthTestProfile.RIGHT_AUDIENCE_CLIENT_ID;
import static org.yardship.integration.adapters.in.mcp.SurfaceAuthTestProfile.TEST_CLIENT_SECRET;
import static org.yardship.integration.adapters.in.mcp.SurfaceAuthTestProfile.TEST_PASSWORD;
import static org.yardship.integration.adapters.in.mcp.SurfaceAuthTestProfile.TEST_USERNAME;
import static org.yardship.integration.adapters.in.mcp.SurfaceAuthTestProfile.WRONG_AUDIENCE_CLIENT_ID;

/**
 * Auth-ON integration coverage for the MCP endpoint (docs/adr/0026, docs/adr/0028, issue 01), run
 * against real Keycloak Dev Services under {@link SurfaceAuthTestProfile} (real issuer discovery
 * + JWKS — no mocked identities, per the shared plan). The auth-OFF regression stays entirely in
 * {@link ApplicationMcpServerIT}, which this class does not touch.
 *
 * <p>Now covers the role-gated model (generalized from "any valid token" to "a valid token
 * carrying the {@code pu2d-mcp} role"): a token for the right audience but the wrong/missing role
 * (user {@code bob}, who has no {@code pu2d-mcp} realm role) must be rejected with 403, distinct
 * from the 401s below (which are pure authentication failures — no/invalid/wrong-audience token).
 *
 * <p>The two 401 rejection cases (no token / wrong-audience token) go through {@link McpAssured}
 * itself via {@code setExpectConnectFailure(Consumer)}, which asserts the HTTP status code of the
 * (failed) connect attempt — no need to hand-roll a raw HTTP call. The happy path and the 403 case
 * also go through {@link McpAssured}, adding the bearer token as an additional header via
 * {@code setAdditionalHeaders(...)} (this McpAssured version has no direct
 * {@code setBearerToken}), per the acceptance criteria.
 *
 * <p><b>Deviation from the original tester assumption:</b> {@code io.quarkus.test.oidc.client.
 * OidcTestClient} (Vert.x {@code WebClient}-based) reproducibly returned a JSON response with no
 * {@code access_token} field against this Dev-Services-backed realm in this environment, even
 * though an identical password-grant request made with a plain {@link HttpClient} against the
 * same token endpoint succeeded. Rather than depend on that unexplained mismatch, token
 * acquisition here is a small direct password-grant POST via {@link HttpClient} — same grant,
 * same realm, same clients, just a different (verified-working) HTTP client.
 */
@QuarkusTest
@TestProfile(SurfaceAuthTestProfile.class)
public class McpOidcAuthEnforcedIT {

    // The tool-call happy-path test below needs a resolvable app so it doesn't hit the real
    // (github-release, repo-less) test fixture in application.properties — mocked the same way
    // ApplicationMcpServerIT mocks it, since this auth-on class is not exercising tool business
    // logic, only that an authenticated caller can reach it.
    @InjectMock
    ApplicationVersionPort applicationVersionPort;

    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private static final Pattern ACCESS_TOKEN_PATTERN = Pattern.compile("\"access_token\":\"([^\"]+)\"");

    private static MultiMap bearerHeader(String token) {
        return MultiMap.caseInsensitiveMultiMap().set("Authorization", "Bearer " + token);
    }

    /** Password-grant token request against the Dev-Services-backed test realm's token endpoint. */
    private static String fetchAccessToken(String clientId, String clientSecret) {
        return fetchAccessToken(clientId, clientSecret, TEST_USERNAME, TEST_PASSWORD);
    }

    /** Password-grant token request for a specific user, e.g. the role-less {@code bob}. */
    private static String fetchAccessToken(String clientId, String clientSecret, String username, String password) {
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

    @Test
    void mcpEndpoint_withoutBearerToken_isRejectedWithUnauthorized() {
        McpAssured.newStreamableClient()
                .setMcpPath("/api/mcp")
                .setExpectConnectFailure(failure -> assertEquals(401, failure.statusCode(),
                        "an unauthenticated request must be rejected with 401"))
                .build()
                .connect();
    }

    @Test
    void mcpEndpoint_withTokenForWrongAudience_isRejectedWithUnauthorized() {
        String wrongAudienceToken = fetchAccessToken(WRONG_AUDIENCE_CLIENT_ID, TEST_CLIENT_SECRET);

        McpAssured.newStreamableClient()
                .setMcpPath("/api/mcp")
                .setAdditionalHeaders(message -> bearerHeader(wrongAudienceToken))
                .setExpectConnectFailure(failure -> assertEquals(401, failure.statusCode(),
                        "a token from the right issuer but the wrong audience ("
                                + WRONG_AUDIENCE_CLIENT_ID + ") must be rejected with 401"))
                .build()
                .connect();
    }

    @Test
    void mcpEndpoint_withValidTokenButWithoutRequiredRole_isRejectedWithForbidden() {
        // bob carries the right audience (mcp-client) but lacks the pu2d-mcp realm role — this
        // must be distinguished from the 401s above: authentication succeeds (the token itself is
        // valid for the configured issuer+audience), but authorization fails.
        String tokenWithoutRole = fetchAccessToken(RIGHT_AUDIENCE_CLIENT_ID, TEST_CLIENT_SECRET,
                NO_ROLE_USERNAME, NO_ROLE_PASSWORD);

        McpAssured.newStreamableClient()
                .setMcpPath("/api/mcp")
                .setAdditionalHeaders(message -> bearerHeader(tokenWithoutRole))
                .setExpectConnectFailure(failure -> assertEquals(403, failure.statusCode(),
                        "a valid token for the configured audience but missing the required "
                                + "pu2d-mcp role must be rejected with 403, not 401"))
                .build()
                .connect();
    }

    @Test
    void mcpEndpoint_withValidTokenForConfiguredAudience_toolsListSucceeds() {
        String validToken = fetchAccessToken(RIGHT_AUDIENCE_CLIENT_ID, TEST_CLIENT_SECRET);

        McpStreamableTestClient client = McpAssured.newStreamableClient()
                .setMcpPath("/api/mcp")
                .setAdditionalHeaders(message -> bearerHeader(validToken))
                .build()
                .connect();

        client.when()
                .toolsList(page -> assertNotNull(page.findByName("get_application"),
                        "get_application tool must be registered for an authenticated caller "
                                + "bearing the configured audience " + CONFIGURED_AUDIENCE))
                .thenAssertResults();
    }

    @Test
    void mcpEndpoint_withValidTokenForConfiguredAudience_toolCallSucceeds() {
        Instant now = Instant.parse("2026-07-01T10:00:00Z");
        when(applicationVersionPort.getApplications()).thenReturn(List.of(new VersionApplication(
                "test-app",
                SideObservation.resolved(new SemverVersion("1.0.0"), now),
                SideObservation.resolved(new SemverVersion("1.0.0"), now))));
        String validToken = fetchAccessToken(RIGHT_AUDIENCE_CLIENT_ID, TEST_CLIENT_SECRET);

        McpStreamableTestClient client = McpAssured.newStreamableClient()
                .setMcpPath("/api/mcp")
                .setAdditionalHeaders(message -> bearerHeader(validToken))
                .build()
                .connect();

        client.when()
                .toolsCall("get_application", Map.of("name", "test-app"), response ->
                        assertFalse(response.isError(),
                                "an authenticated caller with the configured audience must be "
                                        + "able to call tools"))
                .thenAssertResults();
    }
}
