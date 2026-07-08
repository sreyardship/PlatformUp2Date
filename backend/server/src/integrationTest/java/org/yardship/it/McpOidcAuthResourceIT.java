package org.yardship.it;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The native auth-on MCP smoke (issue 04, docs/adr/0026). Runs against the <em>built</em>
 * artifact — the GraalVM native binary in CI's native pipeline, the JVM-packaged jar locally via
 * {@code gradle quarkusIntTest} — with {@link KeycloakContainerResource} providing a real
 * Keycloak issuer (a Testcontainers container, not Dev Services; see that class's Javadoc for
 * why Dev Services can't compose with an already-launched artifact the way it does for the JVM
 * {@code @QuarkusTest} auth-on suite).
 *
 * <p>{@code quarkus-oidc} brings JWT parsing, JWKS fetch/caching, and OIDC discovery — all
 * reflection/TLS-sensitive machinery under GraalVM (ADR 0025 prior art: this project has been
 * bitten by native reachability regressions that only a native-run test can see). A JVM-mode
 * green alone would not be evidence the same machinery survives native-image analysis.
 *
 * <p>Uses a plain {@link HttpClient} rather than {@code McpAssured}/RestAssured (neither is on
 * the {@code integrationTest} source set's classpath, and both are designed for in-JVM
 * {@code @QuarkusTest}, not a black-box process): a single JSON-RPC POST against the Streamable
 * HTTP endpoint is enough, since {@code streamable.auto-init} means each request auto-initialises
 * its own throwaway session (docs/adr/0004) — no separate MCP `initialize` handshake is needed
 * first.
 */
@QuarkusIntegrationTest
@QuarkusTestResource(value = KeycloakContainerResource.class, restrictToAnnotatedClass = true)
class McpOidcAuthResourceIT {

    @TestHTTPResource("/api/mcp")
    URL mcpUrl;

    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private static final Pattern ACCESS_TOKEN_PATTERN = Pattern.compile("\"access_token\":\"([^\"]+)\"");

    private static final String TOOLS_LIST_REQUEST =
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}";

    @Test
    void mcpEndpoint_withoutBearerToken_isRejectedWithChallenge() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(mcpUrl.toURI())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        assertTrue(response.statusCode() == 401,
                "an unauthenticated /api/mcp request must be rejected with 401 from the built "
                        + "artifact, got " + response.statusCode() + ": " + response.body());
        String wwwAuthenticate = response.headers().firstValue("WWW-Authenticate").orElse(null);
        assertNotNull(wwwAuthenticate,
                "401 response from the built artifact must carry a WWW-Authenticate header");
        assertTrue(wwwAuthenticate.contains("Bearer"),
                "WWW-Authenticate challenge must be a Bearer challenge: " + wwwAuthenticate);
        assertTrue(wwwAuthenticate.contains("resource_metadata"),
                "WWW-Authenticate challenge must carry the RFC 9728 resource_metadata pointer "
                        + "(docs/adr/0026, issue 02): " + wwwAuthenticate);
    }

    @Test
    void mcpEndpoint_withValidToken_toolsListSucceeds() throws Exception {
        String issuer = KeycloakContainerResource.issuer();
        assertNotNull(issuer, "KeycloakContainerResource must have started and recorded its issuer URL");
        String validToken = fetchAccessToken(issuer, "mcp-client", "secret");

        HttpRequest request = HttpRequest.newBuilder(mcpUrl.toURI())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Bearer " + validToken)
                .POST(HttpRequest.BodyPublishers.ofString(TOOLS_LIST_REQUEST))
                .build();

        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        HttpResponse<String> response = null;
        while (Instant.now().isBefore(deadline)) {
            response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                break;
            }
            Thread.sleep(500);
        }
        assertNotNull(response, "no response received from /api/mcp");
        assertTrue(response.statusCode() == 200,
                "an authenticated tools/list call must succeed against the built artifact, got "
                        + response.statusCode() + ": " + response.body());
        String body = extractJsonPayload(response);
        assertTrue(body.contains("\"name\":\"get_application\""),
                "tools/list result must register the get_application tool: " + body);
    }

    /** Password-grant token request against the Keycloak container's token endpoint. */
    private static String fetchAccessToken(String issuer, String clientId, String clientSecret) {
        String tokenUrl = issuer + "/protocol/openid-connect/token";
        String form = "grant_type=password"
                + "&username=alice"
                + "&password=alice"
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
                fail("Token request to " + tokenUrl + " for client " + clientId
                        + " failed: status=" + response.statusCode() + " body=" + response.body());
            }
            return matcher.group(1);
        } catch (java.io.IOException | InterruptedException e) {
            throw new IllegalStateException("Token request to " + tokenUrl + " failed", e);
        }
    }

    /**
     * The Streamable HTTP transport may answer a single JSON-RPC POST either as a plain JSON body
     * or as a one-shot {@code text/event-stream} (a single {@code data:} line carrying the same
     * JSON) depending on negotiated content type; this normalises both shapes to the raw JSON text
     * so callers can assert on it uniformly.
     */
    private static String extractJsonPayload(HttpResponse<String> response) {
        String contentType = response.headers().firstValue("content-type").orElse("");
        String body = response.body();
        if (!contentType.contains("text/event-stream")) {
            return body;
        }
        StringBuilder data = new StringBuilder();
        for (String line : body.split("\\R")) {
            if (line.startsWith("data:")) {
                data.append(line.substring("data:".length()).trim());
            }
        }
        return data.toString();
    }
}
