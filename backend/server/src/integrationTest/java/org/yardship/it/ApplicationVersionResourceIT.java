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
 * Black-box integration test that runs against the <em>built</em> artifact — in
 * CI's native pipeline that is the GraalVM native binary. Its job is to catch
 * native-image regressions that JVM-mode unit tests cannot see: e.g. a REST-client
 * provider (the {@code VersionResponseExceptionMapper}) or a DTO that can't be
 * reflectively handled in native. If the {@code ApplicationVersionClient} bean
 * fails to construct in native, the scheduled scrape never populates the cache and
 * {@code /api/v1/version} stays empty ({@code {}}), which fails this test.
 *
 * <p>The {@code good-app} {@code github-release} fixture
 * ({@link WireMockVersionResource}) serves its releases behind a 301 redirect (the
 * {@code /repos/.../releases} -> {@code /repositories/<id>/releases} move GitHub actually performed
 * for {@code vmware-tanzu/velero} — see ADR-0029). This test observing {@code latest = 2.0.0} through
 * the built artifact's public HTTP surface is therefore also proof that the redirect was followed
 * end to end (composition root through the driven adapter), not just that the direct path works.
 */
@QuarkusIntegrationTest
@QuarkusTestResource(value = WireMockVersionResource.class, restrictToAnnotatedClass = true)
class ApplicationVersionResourceIT {

    @TestHTTPResource("/api/v1/version")
    URL versionUrl;

    @Test
    void versionEndpoint_exposesScrapedApp() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(versionUrl.toURI()).GET().build();

        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        String body = "";
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            body = response.body();
            if (response.statusCode() == 200 && body.contains("good-app")) {
                assertTrue(body.contains("1.0.0"), "expected current version in: " + body);
                assertTrue(body.contains("2.0.0"), "expected latest version in: " + body);
                // configErrors is a List field, and Jackson instantiates ConfigErrorEntry[]
                // reflectively to build its serialiser — for the DECLARED type, so a clean app's
                // empty array exercises it too. Without the array class registered for reflection
                // this endpoint returns 500 in native while every JVM test stays green, which is
                // exactly how it once shipped. Asserting the field explicitly keeps that pinned
                // even if a future change stops the version assertions above from covering it.
                assertTrue(body.contains("\"configErrors\""),
                        "expected configErrors on the native payload (ADR-0032): " + body);
                return;
            }
            Thread.sleep(500);
        }
        fail("Timed out waiting for /api/v1/version to expose the scraped app. Last body: " + body);
    }
}
