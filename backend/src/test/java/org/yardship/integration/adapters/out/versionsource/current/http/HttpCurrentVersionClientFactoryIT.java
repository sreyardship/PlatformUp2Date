package org.yardship.integration.adapters.out.versionsource.current.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yardship.adapters.out.versionsource.auth.FileBearerAuthFilter;
import org.yardship.adapters.out.versionsource.current.http.HttpCurrentVersionClient;
import org.yardship.adapters.out.versionsource.current.http.HttpCurrentVersionClientFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Integration test for the real {@link HttpCurrentVersionClientFactory} collaborator — the only
 * Arc-bound piece left after {@code HttpCurrentSource} became a pure POJO. Verifies it builds a
 * working {@link HttpCurrentVersionClient} for a given base URL, with the existing
 * {@link org.yardship.adapters.out.versionsource.VersionResponseExceptionMapper} registered so a
 * non-2xx upstream surfaces as a thrown exception.
 *
 * <p>{@code @QuarkusTest} is used because the factory's {@code build(...)} wraps
 * {@code QuarkusRestClientBuilder}, which needs a running Quarkus context — matching the existing IT
 * style ({@code HttpCurrentSourceIT}, {@code GithubReleaseLatestSourceIT}).
 */
@QuarkusTest
class HttpCurrentVersionClientFactoryIT {

    static WireMockServer wireMockServer;

    @Inject
    HttpCurrentVersionClientFactory clientFactory;

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(options().port(8089));
        wireMockServer.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMockServer.stop();
    }

    @BeforeEach
    void resetStubs() {
        wireMockServer.resetAll();
    }

    @Test
    void build_returnsAClient_thatReadsJsonFromTheConfiguredBaseUrl() {
        wireMockServer.stubFor(get(urlEqualTo("/current"))
                .willReturn(jsonResponse(200, "{\"version\":\"1.0.0\"}")));

        HttpCurrentVersionClient client =
                clientFactory.build("http://localhost:8089/current", Optional.empty(), Optional.empty());
        JsonNode body = client.getCurrentVersion();

        assertEquals("1.0.0", body.at("/version").textValue());
    }

    @Test
    void build_registersTheExceptionMapper_soANon2xxResponseThrows() {
        wireMockServer.stubFor(get(urlEqualTo("/current"))
                .willReturn(jsonResponse(403, "{\"message\":\"forbidden\"}")));

        HttpCurrentVersionClient client =
                clientFactory.build("http://localhost:8089/current", Optional.empty(), Optional.empty());

        assertThrows(RuntimeException.class, client::getCurrentVersion);
    }

    @Test
    void build_withoutAnAuthFilter_sendsNoAuthorizationHeader() {
        // Security-critical guardrail rehomed from HttpCurrentSourceIT: building with
        // Optional.empty() (the current leg's only mode this slice) must never carry an
        // Authorization header — the current leg hits our own deployment endpoints, where a
        // GitHub (or any other) token would be a secret-exfiltration bug.
        wireMockServer.stubFor(get(urlEqualTo("/current"))
                .willReturn(jsonResponse(200, "{\"version\":\"1.0.0\"}")));

        clientFactory.build("http://localhost:8089/current", Optional.empty(), Optional.empty())
                .getCurrentVersion();

        wireMockServer.verify(getRequestedFor(urlEqualTo("/current"))
                .withHeader("Authorization", absent()));
    }

    @Test
    void build_withAFileBearerAuthFilter_putsTheFilesTokenOnTheWire(@TempDir Path dir) throws IOException {
        Path tokenFile = dir.resolve("token");
        Files.writeString(tokenFile, "  file-tok\n");
        wireMockServer.stubFor(get(urlEqualTo("/current"))
                .willReturn(jsonResponse(200, "{\"version\":\"1.0.0\"}")));

        clientFactory.build("http://localhost:8089/current",
                        Optional.of(new FileBearerAuthFilter(tokenFile.toString())), Optional.empty())
                .getCurrentVersion();

        wireMockServer.verify(getRequestedFor(urlEqualTo("/current"))
                .withHeader("Authorization", equalTo("Bearer file-tok")));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder jsonResponse(int status, String body) {
        return aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body);
    }
}
