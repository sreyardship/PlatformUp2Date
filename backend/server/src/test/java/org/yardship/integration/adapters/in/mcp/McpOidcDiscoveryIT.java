package org.yardship.integration.adapters.in.mcp;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RFC 9728 discovery coverage (docs/adr/0026, issue 02) for the auth-ON MCP endpoint: the
 * {@code WWW-Authenticate} challenge on the endpoint enforced in slice 01, and the
 * protected-resource metadata document that challenge's {@code resource_metadata} pointer
 * resolves to. A deliberate sibling of {@link McpOidcAuthEnforcedIT} (rather than an addition to
 * it) so that class stays single-purpose (token enforcement only) while this one owns discovery.
 *
 * <p>Uses REST Assured directly instead of {@link io.quarkiverse.mcp.server.test.McpAssured} for
 * the 401 case: McpAssured's {@code setExpectConnectFailure} only exposes the failure's status
 * code, not response headers, and this test needs to read {@code WWW-Authenticate} itself.
 *
 * <p>The well-known path is deliberately NOT hard-coded: it is derived from the
 * {@code resource_metadata} URL the server itself advertises in the challenge, per the issue's
 * guidance (Quarkus may serve it at the host-root {@code /.well-known/oauth-protected-resource}
 * rather than a per-resource variant) — this test only asserts the invariant that matters
 * (unrelocated to under {@code /api}, per ADR 0026's consequence about the HTTPRoute).
 */
@QuarkusTest
@TestProfile(SurfaceAuthTestProfile.class)
public class McpOidcDiscoveryIT {

    private static final Pattern RESOURCE_METADATA_PATTERN =
            Pattern.compile("resource_metadata=\"([^\"]+)\"");

    /** POSTs an unauthenticated request to /api/mcp and returns the raw response for inspection. */
    private static Response unauthenticatedMcpRequest() {
        return given()
                .contentType("application/json")
                .body("{}")
                .when()
                .post("/api/mcp");
    }

    private static String resourceMetadataUrlFromChallenge() {
        String wwwAuthenticate = unauthenticatedMcpRequest()
                .then()
                .statusCode(401)
                .extract()
                .header("WWW-Authenticate");
        assertNotNull(wwwAuthenticate,
                "401 response from /api/mcp must carry a WWW-Authenticate header");
        Matcher matcher = RESOURCE_METADATA_PATTERN.matcher(wwwAuthenticate);
        assertTrue(matcher.find(),
                "WWW-Authenticate header must carry a resource_metadata pointer: " + wwwAuthenticate);
        return matcher.group(1);
    }

    @Test
    void mcpEndpoint_withoutBearerToken_challengeCarriesResourceMetadataPointer() {
        String resourceMetadataUrl = resourceMetadataUrlFromChallenge();

        assertTrue(resourceMetadataUrl.contains("/.well-known/oauth-protected-resource"),
                "resource_metadata pointer must reference the RFC 9728 well-known path: "
                        + resourceMetadataUrl);
    }

    @Test
    void protectedResourceMetadataUrl_isNotRelocatedUnderApiPrefix() {
        String resourceMetadataUrl = resourceMetadataUrlFromChallenge();
        String path = URI.create(resourceMetadataUrl).getPath();

        assertTrue(path.startsWith("/.well-known/oauth-protected-resource"),
                "the well-known path must live at the host root, NOT be relocated under /api "
                        + "(docs/adr/0026 consequence re: the HTTPRoute) - got path: " + path);
    }

    @Test
    void protectedResourceMetadata_isServedAtTheAdvertisedPath_andNamesConfiguredIssuer() {
        String resourceMetadataUrl = resourceMetadataUrlFromChallenge();
        String path = URI.create(resourceMetadataUrl).getPath();
        String configuredIssuer = ConfigProvider.getConfig()
                .getValue("quarkus.oidc.auth-server-url", String.class);

        JsonPath metadata = given()
                .when()
                .get(path)
                .then()
                .statusCode(200)
                .extract()
                .body()
                .jsonPath();

        List<String> authorizationServers = metadata.getList("authorization_servers", String.class);
        assertNotNull(authorizationServers,
                "protected-resource metadata must carry an authorization_servers array");
        assertTrue(authorizationServers.contains(configuredIssuer),
                "authorization_servers must name the configured issuer " + configuredIssuer
                        + ", got: " + authorizationServers);

        String resource = metadata.getString("resource");
        assertNotNull(resource, "protected-resource metadata must carry a 'resource' field");
        assertTrue(resource.contains("/api/mcp"),
                "the metadata's 'resource' field must identify the protected /api/mcp resource: "
                        + resource);
    }
}
