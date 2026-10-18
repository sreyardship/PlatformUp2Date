package org.yardship.integration.adapters.out.versionsource.latest.githubrelease;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.VersionFetchException;
import org.yardship.adapters.out.versionsource.latest.LatestVersionSourceFactory;
import org.yardship.adapters.out.versionsource.latest.githubrelease.GithubReleaseLatestSourceFactory;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import org.yardship.core.ports.out.LatestVersionSource;
import org.yardship.integration.adapters.out.versionsource.latest.LatestSourceFragment;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the real {@code github-release} latest-version kind, built end to end
 * through {@link GithubReleaseLatestSourceFactory#create} against a standalone WireMock server on
 * port 8089 standing in for the GitHub API host.
 *
 * <p>Intentionally thin: selection (ADR-0010) and the 2xx gate are covered by
 * {@code GithubReleaseVersionExtractorTests} with fake responses; redirects, TLS and auth rendering
 * are proven once for the shared transport in {@code HttpTransportIT}. This class proves the kind's
 * own wiring on the wire: the URL the factory assembles from {@code repo} and {@code page-size}, a
 * real release array deserialised, a real non-2xx failing, and which credential reaches GitHub —
 * the fleet-wide {@code github.token}, an app's own {@code auth} block, or none.
 */
@QuarkusTest
class GithubReleaseLatestSourceIT {

    private static final VersionParser SEMVER_PARSER = new VersionParser(VersionScheme.SEMVER);
    private static final String API_BASE_URL = "http://localhost:8089";
    private static final String RELEASES_PATH = "/repos/owner/repo/releases";

    static WireMockServer wireMockServer;

    @Inject
    Instance<LatestVersionSourceFactory> factories;

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
    void version_selectsTheLargestSemverByTagName_fromARealReleaseArray_endToEndThroughTheFactory() {
        wireMockServer.stubFor(get(urlPathEqualTo(RELEASES_PATH))
                .willReturn(jsonResponse(200, """
                        [
                          {"tag_name":"v1.2.0","name":"older","prerelease":false,"draft":false},
                          {"tag_name":"v2.0.0","name":"newest-numerically","prerelease":false,"draft":false},
                          {"tag_name":"v9.0.0","name":"a-prerelease","prerelease":true,"draft":false},
                          {"tag_name":"v8.0.0","name":"a-draft","prerelease":false,"draft":true}
                        ]
                        """)));

        LatestVersionSource source = anonymousFactory().create(fragment(), SEMVER_PARSER);

        assertEquals("2.0.0", source.version().value(),
                "the 'v' prefix is trimmed by the Version primitive; selection is by tag_name among "
                        + "non-prerelease, non-draft releases");
    }

    @Test
    void version_requestsTheReposReleasesPath_withPerPageDefaultingTo30() {
        wireMockServer.stubFor(get(urlPathEqualTo(RELEASES_PATH)).willReturn(jsonResponse(200, ONE_RELEASE)));

        anonymousFactory().create(fragment(), SEMVER_PARSER).version();

        wireMockServer.verify(getRequestedFor(urlPathEqualTo(RELEASES_PATH))
                .withQueryParam("per_page", equalTo("30")));
    }

    @Test
    void version_sendsTheConfiguredPageSize_asPerPage() {
        wireMockServer.stubFor(get(urlPathEqualTo(RELEASES_PATH)).willReturn(jsonResponse(200, ONE_RELEASE)));

        anonymousFactory().create(fragment().pageSize(100), SEMVER_PARSER).version();

        wireMockServer.verify(getRequestedFor(urlPathEqualTo(RELEASES_PATH))
                .withQueryParam("per_page", equalTo("100")));
    }

    @Test
    void version_throwsAVersionFetchException_whenGithubReturnsNon2xx() {
        wireMockServer.stubFor(get(urlPathEqualTo(RELEASES_PATH))
                .willReturn(jsonResponse(403, "{\"message\":\"API rate limit exceeded\"}")));

        LatestVersionSource source = anonymousFactory().create(fragment(), SEMVER_PARSER);

        VersionFetchException ex = assertThrows(VersionFetchException.class, source::version);
        assertEquals(403, ex.status());
        assertTrue(ex.getMessage().contains("API rate limit exceeded"), "must carry the body; was: " + ex.getMessage());
    }

    // --- which credential reaches GitHub ------------------------------------------------------

    @Test
    void version_sendsTheFleetWideGithubToken_asABearer_whenTheAppHasNoAuthBlock() {
        wireMockServer.stubFor(get(urlPathEqualTo(RELEASES_PATH)).willReturn(jsonResponse(200, ONE_RELEASE)));

        factoryWithToken("fleet-token").create(fragment(), SEMVER_PARSER).version();

        wireMockServer.verify(getRequestedFor(urlPathEqualTo(RELEASES_PATH))
                .withHeader("Authorization", equalTo("Bearer fleet-token")));
    }

    @Test
    void version_sendsNoAuthorizationHeader_whenNeitherATokenNorAnAuthBlockIsConfigured() {
        wireMockServer.stubFor(get(urlPathEqualTo(RELEASES_PATH)).willReturn(jsonResponse(200, ONE_RELEASE)));

        anonymousFactory().create(fragment(), SEMVER_PARSER).version();

        wireMockServer.verify(getRequestedFor(urlPathEqualTo(RELEASES_PATH))
                .withHeader("Authorization", absent()));
    }

    @Test
    void version_treatsABlankFleetWideToken_asNoToken() {
        wireMockServer.stubFor(get(urlPathEqualTo(RELEASES_PATH)).willReturn(jsonResponse(200, ONE_RELEASE)));

        factoryWithToken("   ").create(fragment(), SEMVER_PARSER).version();

        wireMockServer.verify(getRequestedFor(urlPathEqualTo(RELEASES_PATH))
                .withHeader("Authorization", absent()));
    }

    @Test
    void version_sendsTheAppsOwnAuthBlock_overTheFleetWideToken() {
        // Catch-all 401 registered FIRST — "last registered wins": only the app's own credential
        // reaches the releases, proving it took precedence over the fleet-wide token.
        wireMockServer.stubFor(get(urlPathEqualTo(RELEASES_PATH)).willReturn(aResponse().withStatus(401)));
        wireMockServer.stubFor(get(urlPathEqualTo(RELEASES_PATH))
                .withHeader("Authorization", equalTo("Bearer app-token"))
                .willReturn(jsonResponse(200, ONE_RELEASE)));

        LatestVersionSource source =
                factoryWithToken("fleet-token").create(fragment().bearerAuth("app-token"), SEMVER_PARSER);

        assertEquals("1.0.0", source.version().value());
        wireMockServer.verify(0, getRequestedFor(urlPathEqualTo(RELEASES_PATH))
                .withHeader("Authorization", equalTo("Bearer fleet-token")));
    }

    /**
     * Proves {@code type: github-release} resolves through {@code VersionSourceResolver} with no
     * central dispatcher edited (ADR-0005) — the factory is genuinely discovered as a CDI bean.
     */
    @Test
    void githubReleaseLatestSourceFactory_isDiscoveredAsACdiBean_exactlyOnce() {
        List<LatestVersionSourceFactory> matching = StreamSupport.stream(factories.spliterator(), false)
                .filter(factory -> "github-release".equals(factory.type()))
                .collect(Collectors.toList());

        assertEquals(1, matching.size(),
                "expected exactly one CDI-discovered LatestVersionSourceFactory for 'github-release', found: "
                        + matching);
    }

    // --- fixtures -----------------------------------------------------------------------------------

    private static final String ONE_RELEASE = """
            [{"tag_name":"v1.0.0","name":"n","prerelease":false,"draft":false}]
            """;

    private static GithubReleaseLatestSourceFactory anonymousFactory() {
        return new GithubReleaseLatestSourceFactory(Optional.empty(), Optional.of(API_BASE_URL));
    }

    private static GithubReleaseLatestSourceFactory factoryWithToken(String token) {
        return new GithubReleaseLatestSourceFactory(Optional.of(token), Optional.of(API_BASE_URL));
    }

    private static LatestSourceFragment fragment() {
        return LatestSourceFragment.ofType("github-release").repo("owner/repo");
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder jsonResponse(int status, String body) {
        return aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body);
    }
}
