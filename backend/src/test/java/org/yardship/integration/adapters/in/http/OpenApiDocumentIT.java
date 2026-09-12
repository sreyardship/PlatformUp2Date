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
 * <p>Asserts that the generated document includes the three real endpoints
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

    /**
     * ADR-0032 / issue 04: {@code configErrors} is a real field on {@code ApplicationStatus}, so
     * the code-first generated spec (ADR-0020 — there is no spec file to hand-edit) must reflect
     * it without any manual step.
     */
    @Test
    void openApiDocument_applicationStatusSchema_includesConfigErrorsField() {
        given()
                .accept("application/json")
                .when()
                .get("/q/openapi")
                .then()
                .statusCode(200)
                .body("components.schemas.ApplicationStatus.properties",
                        org.hamcrest.Matchers.hasKey("configErrors"))
                // Not just "a field called configErrors": pin the array shape and the nested entry
                // schema, so a regression to a scalar or a flattened entry fails here.
                .body("components.schemas.ApplicationStatus.properties.configErrors.type",
                        org.hamcrest.Matchers.equalTo("array"))
                .body("components.schemas.ApplicationStatus.properties.configErrors.items.$ref",
                        org.hamcrest.Matchers.containsString("ConfigErrorEntry"))
                .body("components.schemas", org.hamcrest.Matchers.hasKey("ConfigErrorEntry"));
    }
}
