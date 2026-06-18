package org.yardship.integration.adapters.out.versionclient;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yardship.core.ports.out.VersionRepository;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Integration tests for the authenticated scrape path. A token is configured via
 * {@link GithubAuthTestProfile} ({@code platform-config.github.token=test-token}), so the
 * scrape's {@code latest} leg must carry {@code Authorization: Bearer test-token} while the
 * {@code current} leg (which hits our own deployment endpoints) must carry NO Authorization
 * header — leaking a GitHub token there would be a secret-exfil bug.
 */
@QuarkusTest
@TestProfile(GithubAuthTestProfile.class)
class GithubAuthIT {

    static WireMockServer wireMockServer;

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

    @Inject
    VersionRepository sut;

    @Test
    void latestRequest_carriesBearerToken_currentRequest_doesNot() {
        // Arrange — a fully valid app, both legs respond with parseable JSON
        wireMockServer.stubFor(get(urlEqualTo("/good/current"))
                .willReturn(jsonResponse(200, "{\"version\":\"1.0.0\"}")));
        wireMockServer.stubFor(get(urlEqualTo("/good/latest"))
                .willReturn(jsonResponse(200, "{\"name\":\"v2.0.0\"}")));

        // Act
        sut.scrape();

        // Assert — the GitHub (latest) leg authenticates with the configured token
        wireMockServer.verify(getRequestedFor(urlEqualTo("/good/latest"))
                .withHeader("Authorization", equalTo("Bearer test-token")));

        // Security-critical: the current leg hits our own deployment, so it must NOT
        // carry the GitHub token. Absence of the Authorization header is the guardrail
        // against secret exfiltration.
        wireMockServer.verify(getRequestedFor(urlEqualTo("/good/current"))
                .withHeader("Authorization", absent()));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder jsonResponse(int status, String body) {
        return aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body);
    }
}
