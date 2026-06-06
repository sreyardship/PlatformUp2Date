package org.yardship.integration.adapters.out.versionclient;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yardship.core.domain.primitives.VersionApplication;
import org.yardship.core.ports.out.VersionRepository;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration tests for the real {@link org.yardship.adapters.out.versionclient.ApplicationVersionClient}
 * adapter against a standalone WireMock server. Two apps are configured via
 * {@link WireMockTestProfile}: good-app and bad-app, both hitting WireMock on port 8089.
 *
 * The core regression these tests protect: one failing endpoint must NOT abort the whole
 * scrape. Each app is isolated, so a bad-app failure still yields the good-app result.
 */
@QuarkusTest
@TestProfile(WireMockTestProfile.class)
class ApplicationVersionClientIT {

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
    void getAllVersionApplications_isolatesFailures_oneBadAppDoesNotBlankTheRest() {
        // Arrange — good-app fully valid
        wireMockServer.stubFor(get(urlEqualTo("/good/current"))
                .willReturn(jsonResponse(200, "{\"version\":\"1.0.0\"}")));
        wireMockServer.stubFor(get(urlEqualTo("/good/latest"))
                .willReturn(jsonResponse(200, "{\"name\":\"v2.0.0\"}")));

        // bad-app: current ok, latest returns 403 rate-limit JSON
        wireMockServer.stubFor(get(urlEqualTo("/bad/current"))
                .willReturn(jsonResponse(200, "{\"version\":\"1.0.0\"}")));
        wireMockServer.stubFor(get(urlEqualTo("/bad/latest"))
                .willReturn(jsonResponse(403, "{\"message\":\"API rate limit exceeded\"}")));

        // Act
        List<VersionApplication> result = sut.getAllVersionApplications();

        // Assert — only the good app survives, with correct versions
        assertEquals(1, result.size());
        VersionApplication app = result.getFirst();
        assertEquals("good-app", app.name());
        assertEquals("1.0.0", app.current().value());
        assertEquals("2.0.0", app.latest().value());
    }

    @Test
    void getAllVersionApplications_returnsBothApps_whenAllEndpointsValid() {
        // Arrange — both apps fully valid
        wireMockServer.stubFor(get(urlEqualTo("/good/current"))
                .willReturn(jsonResponse(200, "{\"version\":\"1.0.0\"}")));
        wireMockServer.stubFor(get(urlEqualTo("/good/latest"))
                .willReturn(jsonResponse(200, "{\"name\":\"v2.0.0\"}")));
        wireMockServer.stubFor(get(urlEqualTo("/bad/current"))
                .willReturn(jsonResponse(200, "{\"version\":\"3.0.0\"}")));
        wireMockServer.stubFor(get(urlEqualTo("/bad/latest"))
                .willReturn(jsonResponse(200, "{\"name\":\"v4.0.0\"}")));

        // Act
        List<VersionApplication> result = sut.getAllVersionApplications();

        // Assert
        assertEquals(2, result.size());
    }

    @Test
    void getAllVersionApplications_skipsApp_whenEndpointReturnsHtmlLoginBody() {
        // Arrange — good-app valid
        wireMockServer.stubFor(get(urlEqualTo("/good/current"))
                .willReturn(jsonResponse(200, "{\"version\":\"1.0.0\"}")));
        wireMockServer.stubFor(get(urlEqualTo("/good/latest"))
                .willReturn(jsonResponse(200, "{\"name\":\"v2.0.0\"}")));

        // bad-app current returns a long (>512 char) HTML login page instead of JSON.
        // This exercises the truncation-in-log + skip path.
        wireMockServer.stubFor(get(urlEqualTo("/bad/current"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody(longHtmlLoginPage())));
        wireMockServer.stubFor(get(urlEqualTo("/bad/latest"))
                .willReturn(jsonResponse(200, "{\"name\":\"v2.0.0\"}")));

        // Act
        List<VersionApplication> result = sut.getAllVersionApplications();

        // Assert — bad-app excluded, good-app survives
        assertEquals(1, result.size());
        assertEquals("good-app", result.getFirst().name());
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder jsonResponse(int status, String body) {
        return aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body);
    }

    private static String longHtmlLoginPage() {
        StringBuilder sb = new StringBuilder("<html><body><a href=\"https://auth.example/oauth2\">Found</a>");
        while (sb.length() <= 512) {
            sb.append("<p>Please sign in to continue to the protected resource.</p>");
        }
        sb.append("</body></html>");
        return sb.toString();
    }
}
