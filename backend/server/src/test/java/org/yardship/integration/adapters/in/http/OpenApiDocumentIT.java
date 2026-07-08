package org.yardship.integration.adapters.in.http;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Tripwire for ADR 0020 ("API is code-first"): {@code GET /q/openapi} must serve a
 * spec generated from the real JAX-RS controllers ({@code quarkus-smallrye-openapi}), not
 * the deleted hand-authored YAML. If a controller's path changes without this test being
 * updated, the mismatch will surface here rather than silently drifting in stale docs.
 *
 * <p>Slice 01: asserts the generated document's paths include the three real endpoints
 * ({@code /api/v1/version}, {@code /api/v1/scrape}, {@code /api/v1/scrape/applications})
 * and that the info title is the project name, not a Quarkus placeholder like
 * "Generated API".
 */
@QuarkusTest
class OpenApiDocumentIT {

    @Test
    void openApiDocument_includesRealApiPaths_andProjectTitle() {
        given()
                // Force JSON: the /q/openapi endpoint defaults to YAML without an explicit
                // Accept header, and rest-assured's body()/JsonPath matchers need JSON.
                .accept("application/json")
                .when()
                .get("/q/openapi")
                .then()
                .statusCode(200)
                .body("info.title", equalTo("PlatformUp2Date"))
                .body("paths", org.hamcrest.Matchers.hasKey("/api/v1/version"))
                .body("paths", org.hamcrest.Matchers.hasKey("/api/v1/scrape"))
                .body("paths", org.hamcrest.Matchers.hasKey("/api/v1/scrape/applications"));
    }
}
