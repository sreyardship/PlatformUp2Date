package org.yardship.integration.adapters.out.versionsource;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.GithubReleaseLatestSource;
import org.yardship.core.domain.primitives.Version;

import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration test for the real {@link GithubReleaseLatestSource} adapter against a standalone
 * WireMock server on port 8089. {@code GithubReleaseLatestSource} wraps the existing
 * {@link org.yardship.adapters.out.versionclient.GithubReleaseClient} REST client and OWNS the
 * GitHub auth concern: when constructed with a token it registers the shared, scheme-generic
 * {@link org.yardship.adapters.out.versionclient.BearerAuthFilter} so the latest leg carries
 * {@code Authorization: Bearer <token>}; when constructed without one it sends no auth header.
 * (Issue 03 generalized the former GitHub-specific {@code GithubAuthFilter} into this shared
 * filter — see {@code BearerAuthFilterTests} for the filter's own unit coverage.)
 *
 * <p>This rehomes the {@code latest}-leg coverage of the deleted {@code ApplicationVersionClientIT}
 * / {@code GithubAuthIT}: it resolves the release {@code name} (with the {@code v}-prefix the
 * {@link Version} primitive trims) into a {@link Version}, and the bearer header is present/absent
 * exactly as the token is present/absent.
 *
 * <p>{@code @QuarkusTest} is used because {@code QuarkusRestClientBuilder} needs a running Quarkus
 * context — matching the existing IT style. The source is constructed directly (plain object) with
 * a base URL plus an {@link Optional} token.
 */
@QuarkusTest
class GithubReleaseLatestSourceIT {

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
    void read_resolvesReleaseName_intoVersion() {
        wireMockServer.stubFor(get(urlEqualTo("/latest"))
                .willReturn(jsonResponse(200, "{\"name\":\"v2.0.0\"}")));

        GithubReleaseLatestSource source =
                new GithubReleaseLatestSource("http://localhost:8089/latest", Optional.empty());

        Version result = source.version();

        assertEquals("2.0.0", result.value(), "the 'v' prefix is trimmed by the Version primitive");
    }

    @Test
    void read_sendsBearerToken_whenConstructedWithAToken() {
        wireMockServer.stubFor(get(urlEqualTo("/latest"))
                .willReturn(jsonResponse(200, "{\"name\":\"v2.0.0\"}")));

        GithubReleaseLatestSource source =
                new GithubReleaseLatestSource("http://localhost:8089/latest", Optional.of("test-token"));

        source.version();

        wireMockServer.verify(getRequestedFor(urlEqualTo("/latest"))
                .withHeader("Authorization", equalTo("Bearer test-token")));
    }

    @Test
    void read_omitsAuthorizationHeader_whenConstructedWithoutAToken() {
        wireMockServer.stubFor(get(urlEqualTo("/latest"))
                .willReturn(jsonResponse(200, "{\"name\":\"v2.0.0\"}")));

        GithubReleaseLatestSource source =
                new GithubReleaseLatestSource("http://localhost:8089/latest", Optional.empty());

        source.version();

        wireMockServer.verify(getRequestedFor(urlEqualTo("/latest"))
                .withHeader("Authorization", absent()));
    }

    @Test
    void read_omitsAuthorizationHeader_whenTokenIsBlank() {
        // A blank token must be treated as "no auth" — the filter must not be registered.
        wireMockServer.stubFor(get(urlEqualTo("/latest"))
                .willReturn(jsonResponse(200, "{\"name\":\"v2.0.0\"}")));

        GithubReleaseLatestSource source =
                new GithubReleaseLatestSource("http://localhost:8089/latest", Optional.of("   "));

        source.version();

        wireMockServer.verify(getRequestedFor(urlEqualTo("/latest"))
                .withHeader("Authorization", absent()));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder jsonResponse(int status, String body) {
        return aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body);
    }
}
