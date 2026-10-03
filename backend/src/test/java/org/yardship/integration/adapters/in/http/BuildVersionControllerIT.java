package org.yardship.integration.adapters.in.http;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(BuildVersionControllerIT.UnavailableStateProfile.class)
class BuildVersionControllerIT {

    public static class UnavailableStateProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.redis.hosts", "redis://127.0.0.1:1",
                    "quarkus.redis.devservices.enabled", "false",
                    "quarkus.application.version", "9.9.9",
                    "build.version", "9.9.9");
        }
    }

    @Test
    void buildIdentity_isPublicMinimalJson_andNotCacheable() {
        given().when().get("/api/v1/version").then().statusCode(503);
        given().when().get("/api/version").then()
                .statusCode(200)
                .contentType("application/json")
                .header("Cache-Control", "no-store")
                .body("", equalTo(Map.of("version", "dev")));
    }
}
