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
 * The {@code ssh-os-release} native guard. Runs against the <em>built</em> artifact — in CI's native
 * pipeline the GraalVM native binary — and asserts the app resolves its current version over SSH.
 *
 * <p>{@link SshOsReleaseResource} boots an embedded ed25519 MINA server and configures an
 * {@code ssh-os-release} app pointed at it. A successful resolution means the MINA SSH client's
 * connect/auth/exec path (and its reflective key handling) works in the artifact: in native that
 * only holds because the committed {@code META-INF/native-image} reachability metadata makes it so.
 * Delete or corrupt that metadata and this test fails the native build — which is the point.
 */
@QuarkusIntegrationTest
@QuarkusTestResource(value = SshOsReleaseResource.class, restrictToAnnotatedClass = true)
class SshOsReleaseResourceIT {

    @TestHTTPResource("/api/v1/version")
    URL versionUrl;

    @Test
    void sshOsRelease_resolvesCurrentVersionOverSsh() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(versionUrl.toURI()).GET().build();

        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        String body = "";
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            body = response.body();
            if (response.statusCode() == 200 && body.contains("ssh-vm")) {
                assertTrue(body.contains(SshOsReleaseResource.EXPECTED_CURRENT_VERSION),
                        "expected SSH-resolved current version "
                                + SshOsReleaseResource.EXPECTED_CURRENT_VERSION + " in: " + body);
                assertTrue(body.contains(SshOsReleaseResource.EXPECTED_LATEST_VERSION),
                        "expected latest version "
                                + SshOsReleaseResource.EXPECTED_LATEST_VERSION + " in: " + body);
                return;
            }
            Thread.sleep(500);
        }
        fail("Timed out waiting for /api/v1/version to expose the ssh-os-release app. Last body: " + body);
    }
}
