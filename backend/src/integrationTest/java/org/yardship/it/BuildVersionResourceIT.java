package org.yardship.it;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Checks the embedded identity through the packaged image, including native DTO serialization. */
@QuarkusIntegrationTest
@QuarkusTestResource(value = BuildVersionRuntimeResource.class, restrictToAnnotatedClass = true)
class BuildVersionResourceIT {
    @TestHTTPResource("/api/version")
    URL versionUrl;

    @Test
    void buildIdentity_survivesPackaging_andIgnoresRuntimeConfiguration() throws Exception {
        String expected = System.getProperty("expectedBuildVersion");
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(versionUrl.toURI()).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElseThrow()
                .split(";", 2)[0].trim());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("{\"version\":\"" + expected + "\"}", response.body());
    }
}
