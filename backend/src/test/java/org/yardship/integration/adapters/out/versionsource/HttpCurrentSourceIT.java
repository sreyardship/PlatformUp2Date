package org.yardship.integration.adapters.out.versionsource;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.HttpCurrentSource;
import org.yardship.core.domain.primitives.Version;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Integration test for the real {@link HttpCurrentSource} adapter against a standalone WireMock
 * server on port 8089. {@code HttpCurrentSource} wraps the existing
 * {@link org.yardship.adapters.out.versionclient.CurrentVersionClient} REST client and is a plain
 * (non-CDI) object, constructed directly here with a base URL.
 *
 * <p>This rehomes the {@code current}-leg coverage of the deleted {@code ApplicationVersionClientIT}
 * / {@code GithubAuthIT}: it parses {@code {"version":"…"}} into a {@link Version}, and — critically
 * — it NEVER sends an {@code Authorization} header, because the current leg hits our own deployment
 * endpoints where a GitHub token would be a secret-exfiltration bug.
 *
 * <p>{@code @QuarkusTest} is used because {@code QuarkusRestClientBuilder} (used inside the source's
 * construction) needs a running Quarkus context — matching the existing IT style.
 */
@QuarkusTest
class HttpCurrentSourceIT {

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

    @Test
    void read_parsesVersionJson_intoVersion() {
        wireMockServer.stubFor(get(urlEqualTo("/current"))
                .willReturn(jsonResponse(200, "{\"version\":\"1.0.0\"}")));

        HttpCurrentSource source = new HttpCurrentSource("http://localhost:8089/current");

        Version result = source.version();

        assertEquals("1.0.0", result.value());
    }

    @Test
    void read_neverSendsAnAuthorizationHeader() {
        wireMockServer.stubFor(get(urlEqualTo("/current"))
                .willReturn(jsonResponse(200, "{\"version\":\"1.0.0\"}")));

        new HttpCurrentSource("http://localhost:8089/current").version();

        // Security-critical guardrail: the current leg must carry NO Authorization header.
        wireMockServer.verify(getRequestedFor(urlEqualTo("/current"))
                .withHeader("Authorization", absent()));
    }

    @Test
    void read_throws_whenUpstreamReturnsNon2xx() {
        wireMockServer.stubFor(get(urlEqualTo("/current"))
                .willReturn(jsonResponse(403, "{\"message\":\"forbidden\"}")));

        HttpCurrentSource source = new HttpCurrentSource("http://localhost:8089/current");

        // A non-2xx is mapped to a thrown exception by the reused VersionResponseExceptionMapper,
        // so the service's per-app loop can count this app as failed.
        assertThrows(RuntimeException.class, source::version);
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder jsonResponse(int status, String body) {
        return aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body);
    }
}
