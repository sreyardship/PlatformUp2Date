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

        HttpCurrentSource source = new HttpCurrentSource("http://localhost:8089/current", "/version", false);

        Version result = source.version();

        assertEquals("1.0.0", result.value());
    }

    @Test
    void read_withConfiguredTopLevelPointer_resolvesNonStandardKey() {
        // Harbor-shaped response: the version lives under 'harbor_version', not 'version'.
        wireMockServer.stubFor(get(urlEqualTo("/current"))
                .willReturn(jsonResponse(200, "{\"harbor_version\":\"v2.11.1-6b7ecba1\", \"other\":\"x\"}")));

        HttpCurrentSource source = new HttpCurrentSource("http://localhost:8089/current", "/harbor_version", false);

        Version result = source.version();

        // strip-prerelease is false/default here: the prerelease segment must be preserved.
        org.junit.jupiter.api.Assertions.assertTrue(result.value().contains("2.11.1"));
    }

    @Test
    void read_withStripPrereleaseTrue_clearsThePreReleaseSegment() {
        // Harbor-shaped response carrying a build/commit suffix as the prerelease segment.
        wireMockServer.stubFor(get(urlEqualTo("/current"))
                .willReturn(jsonResponse(200, "{\"harbor_version\":\"v2.11.1-6b7ecba1\"}")));

        HttpCurrentSource source = new HttpCurrentSource("http://localhost:8089/current", "/harbor_version", true);

        Version result = source.version();

        assertEquals("2.11.1", result.value());
    }

    @Test
    void read_withStripPrereleaseFalse_preservesThePreReleaseSegment() {
        wireMockServer.stubFor(get(urlEqualTo("/current"))
                .willReturn(jsonResponse(200, "{\"harbor_version\":\"v2.11.1-6b7ecba1\"}")));

        HttpCurrentSource source = new HttpCurrentSource("http://localhost:8089/current", "/harbor_version", false);

        Version result = source.version();

        org.junit.jupiter.api.Assertions.assertTrue(result.value().contains("-6b7ecba1"),
                "with strip-prerelease false/default, the prerelease segment must be preserved; was: "
                        + result.value());
    }

    @Test
    void read_withConfiguredNestedPointer_resolvesNestedKey() {
        wireMockServer.stubFor(get(urlEqualTo("/current"))
                .willReturn(jsonResponse(200, "{\"data\":{\"version\":\"3.4.5\"}}")));

        HttpCurrentSource source = new HttpCurrentSource("http://localhost:8089/current", "/data/version", false);

        Version result = source.version();

        assertEquals("3.4.5", result.value());
    }

    @Test
    void read_throws_whenPointerIsValidButDoesNotResolve() {
        // Harbor 2.13+ shape: 2xx, but 'harbor_version' is gone and only auth_mode-style fields remain.
        wireMockServer.stubFor(get(urlEqualTo("/current"))
                .willReturn(jsonResponse(200, "{\"auth_mode\":\"oidc_auth\"}")));

        HttpCurrentSource source = new HttpCurrentSource("http://localhost:8089/current", "/missing", false);

        // A syntactically valid pointer that does not resolve to a textual value is a clean
        // per-app scrape failure, isolated like any other bad upstream read.
        RuntimeException ex = assertThrows(RuntimeException.class, source::version);
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("/missing"),
                "the failure message must name the unresolved pointer; was: " + ex.getMessage());
        // The (truncated) upstream body must be in the message so a no-401 hardening like Harbor's
        // is diagnosable straight from the scrape log.
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("auth_mode"),
                "the failure message must include the upstream body; was: " + ex.getMessage());
    }

    @Test
    void read_neverSendsAnAuthorizationHeader() {
        wireMockServer.stubFor(get(urlEqualTo("/current"))
                .willReturn(jsonResponse(200, "{\"version\":\"1.0.0\"}")));

        new HttpCurrentSource("http://localhost:8089/current", "/version", false).version();

        // Security-critical guardrail: the current leg must carry NO Authorization header.
        wireMockServer.verify(getRequestedFor(urlEqualTo("/current"))
                .withHeader("Authorization", absent()));
    }

    @Test
    void read_throws_whenUpstreamReturnsNon2xx() {
        wireMockServer.stubFor(get(urlEqualTo("/current"))
                .willReturn(jsonResponse(403, "{\"message\":\"forbidden\"}")));

        HttpCurrentSource source = new HttpCurrentSource("http://localhost:8089/current", "/version", false);

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
