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

import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /**
     * Issue 04 — confirms the two new per-side timestamp gauges are wired end-to-end, and
     * that an Unresolved app (bad-app, whose endpoints always fail) is absent from drift.
     *
     * <p>Strategy: poll until good-app's success gauge appears (proves at least one complete
     * scrape cycle ran). At that point bad-app has also been attempted and is Unresolved.
     * Assert presence/type-header of the new gauge families and absence of bad-app from drift.
     */
    @Test
    void metricsEndpoint_exposesPerSideTimestampGauges_andUnresolvedAppAbsentFromDrift()
            throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(metricsUrl.toURI()).GET().build();

        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        String body = "";
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            body = response.body();
            // Wait until good-app's per-side success gauge is visible — that proves a full scrape
            // cycle has completed and bad-app has been attempted too.
            if (response.statusCode() == 200
                    && body.contains("pu2d_scrape_last_success_timestamp_seconds{app=\"good-app\",side=\"current\"}")) {
                // Both sides of good-app must have a success gauge
                assertTrue(body.contains(
                        "pu2d_scrape_last_success_timestamp_seconds{app=\"good-app\",side=\"latest\"}"),
                        "expected latest-side success gauge for good-app in: " + body);
                // The new gauge family header must be present
                assertTrue(body.contains("# TYPE pu2d_scrape_last_success_timestamp_seconds gauge"),
                        "expected TYPE line for success gauge in: " + body);
                // Unresolved bad-app must not appear in drift — drift is undefined without both values
                assertFalse(body.contains("pu2d_version_drift_level{app=\"bad-app\""),
                        "Unresolved bad-app must NOT appear in drift gauge; body: " + body);
                return;
            }
            Thread.sleep(500);
        }
        fail("Timed out waiting for per-side timestamp gauges. Last body: " + body);
    }
}
