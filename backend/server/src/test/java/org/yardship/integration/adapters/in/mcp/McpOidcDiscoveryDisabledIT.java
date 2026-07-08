package org.yardship.integration.adapters.in.mcp;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/**
 * Auth-OFF regression pin for RFC 9728 discovery (docs/adr/0026, issue 02): with
 * {@code OIDC_ISSUER}/{@code OIDC_AUDIENCE} both unset (today's default, exercised by the
 * default test profile — see {@code ApplicationMcpServerIT}, which this class does not touch), no
 * protected-resource metadata must be served. Deliberately a plain {@code @QuarkusTest} with no
 * {@code @TestProfile}: Keycloak Dev Services stays off, matching production's default-off mode.
 */
@QuarkusTest
public class McpOidcDiscoveryDisabledIT {

    @Test
    void wellKnownProtectedResourcePath_withAuthOff_isNotServed() {
        given()
                .when()
                .get("/.well-known/oauth-protected-resource")
                .then()
                .statusCode(404);
    }

    /**
     * With auth off there is no {@code WWW-Authenticate} challenge to derive the path from (unlike
     * {@link McpOidcDiscoveryIT}, which reads it off the challenge), so the RFC 9728 path-insertion
     * location is hard-coded here. This is the same concrete path that IT derives from the
     * challenge and confirms is served (200) under auth-on — pinning it here from the other side
     * guards against a regression that would start serving the metadata at this suffixed path even
     * while auth is off.
     */
    @Test
    void wellKnownProtectedResourceApiMcpPath_withAuthOff_isNotServed() {
        given()
                .when()
                .get("/.well-known/oauth-protected-resource/api/mcp")
                .then()
                .statusCode(404);
    }
}
