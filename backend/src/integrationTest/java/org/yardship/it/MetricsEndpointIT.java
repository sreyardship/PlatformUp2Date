package org.yardship.it;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Black-box integration test asserting the Prometheus scrape endpoint exposes the
 * {@code pu2d_version_drift_level} gauge for the WireMock-served app. WireMock serves
 * good-app current 1.0.0 / latest 2.0.0 — a major drift, so the value must be 3.
 */
@QuarkusIntegrationTest
@QuarkusTestResource(value = WireMockVersionResource.class, restrictToAnnotatedClass = true)
class MetricsEndpointIT {

    @TestHTTPResource("/metrics")
    URL metricsUrl;

    @Test
    void metricsEndpoint_exposesDriftLevelGauge() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(metricsUrl.toURI()).GET().build();

        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        String body = "";
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            body = response.body();
            if (response.statusCode() == 200 && body.contains("pu2d_version_drift_level{app=\"good-app\"}")) {
                assertTrue(body.contains("# TYPE pu2d_version_drift_level gauge"),
                        "expected TYPE line in: " + body);
                assertTrue(body.contains("pu2d_version_drift_level{app=\"good-app\"} 3"),
                        "expected major drift value 3 in: " + body);
                String contentType = response.headers().firstValue("content-type").orElse("");
                assertTrue(contentType.startsWith("text/plain"),
                        "expected text/plain content-type but was: " + contentType);
                return;
            }
            Thread.sleep(500);
        }
        fail("Timed out waiting for /metrics to expose pu2d_version_drift_level. Last body: " + body);
    }
}
