package org.yardship.integration.adapters.out.versionsource.latest.githubrelease;

import org.yardship.adapters.out.versionsource.VersionFetchException;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yardship.adapters.out.versionsource.latest.githubrelease.GithubReleaseLatestSource;
import org.yardship.core.domain.primitives.SemverVersion;
import org.yardship.core.domain.primitives.VersionValue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Integration test for the real {@link GithubReleaseLatestSource} adapter against a standalone
 * WireMock server on port 8089. {@code GithubReleaseLatestSource} wraps the existing
 * {@link org.yardship.adapters.out.versionsource.latest.githubrelease.GithubReleaseClient} REST client and OWNS the
 * GitHub auth concern: when constructed with a token it registers the shared, scheme-generic
 * {@link org.yardship.adapters.out.versionsource.auth.BearerAuthFilter} so the latest leg carries
 * {@code Authorization: Bearer <token>}; when constructed without one it sends no auth header.
 *
 * <p>The adapter uses GitHub's paged {@code GET /releases} array rather than the single-object
 * {@code GET /releases/latest} endpoint. It selects the maximum semver among non-prerelease,
 * non-draft releases by {@code tag_name} (ADR-0010). These tests exercise real deserialization of
 * the {@code tag_name}, {@code prerelease}, and {@code draft} fields; pure selection logic is
 * covered by {@code GithubReleaseLatestSourceTests}.
 *
 * <p>The two-argument constructor defaults the wire-level {@code per_page} value to 30. Tests that
 * need another page size use the explicit page-size constructor.
 *
 * <p>{@code @QuarkusTest} is used because {@code QuarkusRestClientBuilder} needs a running Quarkus
 * context — matching the existing IT style. The source is constructed directly (plain object) with
 * a base URL plus an {@link Optional} token.
 */
@QuarkusTest
class GithubReleaseLatestSourceIT {
    private static final VersionParser SEMVER_PARSER = new VersionParser(VersionScheme.SEMVER);

    static WireMockServer wireMockServer;

    // A second, independently-hosted HTTP server standing in for a different origin (different
    // port than wireMockServer's 8089), used only by the cross-origin-authorization tests below:
    // a redirect Location pointing here has a different effective port than the request that
    // produced it, so per ADR-0029 the Authorization header must NOT be forwarded to it.
    static WireMockServer crossOriginWireMockServer;

    // An HTTPS-only server (self-signed CN=localhost cert, same fixture as HttpCurrentSourceTlsIT)
    // used only by the HTTPS-to-HTTP downgrade-refusal test. The JVM default trust store is
    // temporarily pointed at this cert's keystore for the duration of that one test so the initial
    // HTTPS leg completes and the downgrade decision — not a TLS trust failure — is what's being
    // observed.
    static WireMockServer httpsWireMockServer;

    private static final String TLS_KEYSTORE_RESOURCE = "tls/wiremock-localhost.p12";
    private static final String TLS_KEYSTORE_PASSWORD = "password";

    @BeforeAll
    static void startWireMock() throws Exception {
        wireMockServer = new WireMockServer(options().port(8089));
        wireMockServer.start();

        crossOriginWireMockServer = new WireMockServer(options().port(8091));
        crossOriginWireMockServer.start();

        String keystorePath = Path.of(GithubReleaseLatestSourceIT.class.getClassLoader()
                .getResource(TLS_KEYSTORE_RESOURCE).toURI()).toString();
        httpsWireMockServer = new WireMockServer(options()
                .httpDisabled(true)
                .dynamicHttpsPort()
                .keystorePath(keystorePath)
                .keystorePassword(TLS_KEYSTORE_PASSWORD)
                .keyManagerPassword(TLS_KEYSTORE_PASSWORD)
                .keystoreType("PKCS12"));
        httpsWireMockServer.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMockServer.stop();
        crossOriginWireMockServer.stop();
        httpsWireMockServer.stop();
    }

    @BeforeEach
    void resetStubs() {
        wireMockServer.resetAll();
        crossOriginWireMockServer.resetAll();
        httpsWireMockServer.resetAll();
    }

    @Test
    void read_selectsTheLargestSemver_byTagName_amongNonPrereleaseNonDraftReleases() {
        wireMockServer.stubFor(get(urlPathEqualTo("/releases"))
                .willReturn(jsonResponse(200, """
                        [
                          {"tag_name":"v1.2.0","name":"older","prerelease":false,"draft":false},
                          {"tag_name":"v2.0.0","name":"newest-numerically","prerelease":false,"draft":false},
                          {"tag_name":"v1.9.0","name":"middle","prerelease":false,"draft":false}
                        ]
                        """)));

        GithubReleaseLatestSource source =
                new GithubReleaseLatestSource("http://localhost:8089", Optional.empty(), SEMVER_PARSER);

        VersionValue result = source.version();

        assertEquals("2.0.0", result.value(),
                "the 'v' prefix is trimmed by the Version primitive; selection is by tag_name, not name");
    }

    @Test
    void read_excludesPrereleaseAndDraftReleases_evenIfNumericallyLarger() {
        wireMockServer.stubFor(get(urlPathEqualTo("/releases"))
                .willReturn(jsonResponse(200, """
                        [
                          {"tag_name":"v1.0.0","name":"stable","prerelease":false,"draft":false},
                          {"tag_name":"v9.0.0","name":"a-prerelease","prerelease":true,"draft":false},
                          {"tag_name":"v8.0.0","name":"a-draft","prerelease":false,"draft":true}
                        ]
                        """)));

        GithubReleaseLatestSource source =
                new GithubReleaseLatestSource("http://localhost:8089", Optional.empty(), SEMVER_PARSER);

        VersionValue result = source.version();

        assertEquals("1.0.0", result.value());
    }

    @Test
    void read_sendsPerPageQueryParam_defaultingTo30() {
        wireMockServer.stubFor(get(urlPathEqualTo("/releases"))
                .willReturn(jsonResponse(200, """
                        [{"tag_name":"v1.0.0","name":"n","prerelease":false,"draft":false}]
                        """)));

        GithubReleaseLatestSource source =
                new GithubReleaseLatestSource("http://localhost:8089", Optional.empty(), SEMVER_PARSER);

        source.version();

        wireMockServer.verify(getRequestedFor(urlPathEqualTo("/releases"))
                .withQueryParam("per_page", equalTo("30")));
    }

    @Test
    void read_sendsBearerToken_whenConstructedWithAToken() {
        wireMockServer.stubFor(get(urlPathEqualTo("/releases"))
                .willReturn(jsonResponse(200, """
                        [{"tag_name":"v2.0.0","name":"n","prerelease":false,"draft":false}]
                        """)));

        GithubReleaseLatestSource source =
                new GithubReleaseLatestSource("http://localhost:8089", Optional.of("test-token"), SEMVER_PARSER);

        source.version();

        wireMockServer.verify(getRequestedFor(urlPathEqualTo("/releases"))
                .withHeader("Authorization", equalTo("Bearer test-token")));
    }

    @Test
    void read_omitsAuthorizationHeader_whenConstructedWithoutAToken() {
        wireMockServer.stubFor(get(urlPathEqualTo("/releases"))
                .willReturn(jsonResponse(200, """
                        [{"tag_name":"v2.0.0","name":"n","prerelease":false,"draft":false}]
                        """)));

        GithubReleaseLatestSource source =
                new GithubReleaseLatestSource("http://localhost:8089", Optional.empty(), SEMVER_PARSER);

        source.version();

        wireMockServer.verify(getRequestedFor(urlPathEqualTo("/releases"))
                .withHeader("Authorization", absent()));
    }

    @Test
    void read_omitsAuthorizationHeader_whenTokenIsBlank() {
        // A blank token must be treated as "no auth" — the filter must not be registered.
        wireMockServer.stubFor(get(urlPathEqualTo("/releases"))
                .willReturn(jsonResponse(200, """
                        [{"tag_name":"v2.0.0","name":"n","prerelease":false,"draft":false}]
                        """)));

        GithubReleaseLatestSource source =
                new GithubReleaseLatestSource("http://localhost:8089", Optional.of("   "), SEMVER_PARSER);

        source.version();

        wireMockServer.verify(getRequestedFor(urlPathEqualTo("/releases"))
                .withHeader("Authorization", absent()));
    }

    // --- ADR-0029 redirect contract -------------------------------------------------------------

    /**
     * Reproduces a moved-repository flow: GitHub moved the {@code vmware-tanzu/velero}
     * repository, so {@code GET /repos/vmware-tanzu/velero/releases?per_page=30} 301s to
     * {@code /repositories/99143276/releases?per_page=30} (relative Location, query string carried
     * through). The fixture ONLY succeeds at the repository-id path, so a pass here proves both the
     * redirect was followed and {@code per_page=30} survived onto the final request.
     */
    @Test
    void read_followsVeleroStyle301Redirect_toRepositoryIdPath_andSelectsLargestEligibleVersion() {
        wireMockServer.stubFor(get(urlPathEqualTo("/repos/vmware-tanzu/velero/releases"))
                .withQueryParam("per_page", equalTo("30"))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", "/repositories/99143276/releases?per_page=30")));
        wireMockServer.stubFor(get(urlPathEqualTo("/repositories/99143276/releases"))
                .withQueryParam("per_page", equalTo("30"))
                .willReturn(jsonResponse(200, """
                        [
                          {"tag_name":"v1.5.0","name":"older","prerelease":false,"draft":false},
                          {"tag_name":"v1.16.0","name":"newest","prerelease":false,"draft":false}
                        ]
                        """)));

        GithubReleaseLatestSource source =
                new GithubReleaseLatestSource("http://localhost:8089/repos/vmware-tanzu/velero",
                        Optional.empty(), 30, SEMVER_PARSER);

        VersionValue result = source.version();

        assertEquals("1.16.0", result.value());
        wireMockServer.verify(getRequestedFor(urlPathEqualTo("/repositories/99143276/releases"))
                .withQueryParam("per_page", equalTo("30")));
    }

    /**
     * Every GET-safe redirect status ADR-0029 requires (301, 302, 303, 307, 308) must be followed
     * to a successful read — an intermediate redirect must never itself be treated as a scrape
     * failure.
     */
    @ParameterizedTest(name = "status {0}")
    @ValueSource(ints = {301, 302, 303, 307, 308})
    void read_followsEveryAdr0029RedirectStatus_forGet(int redirectStatus) {
        wireMockServer.stubFor(get(urlPathEqualTo("/releases"))
                .willReturn(aResponse()
                        .withStatus(redirectStatus)
                        .withHeader("Location", "/redirected-" + redirectStatus + "/releases")));
        wireMockServer.stubFor(get(urlPathEqualTo("/redirected-" + redirectStatus + "/releases"))
                .willReturn(jsonResponse(200, """
                        [{"tag_name":"v3.0.0","name":"n","prerelease":false,"draft":false}]
                        """)));

        GithubReleaseLatestSource source =
                new GithubReleaseLatestSource("http://localhost:8089", Optional.empty(), SEMVER_PARSER);

        VersionValue result = source.version();

        assertEquals("3.0.0", result.value(), "status " + redirectStatus + " must be followed for GET");
    }

    @Test
    void read_followsARelativeLocationHeader() {
        wireMockServer.stubFor(get(urlPathEqualTo("/releases"))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", "/relocated/releases")));
        wireMockServer.stubFor(get(urlPathEqualTo("/relocated/releases"))
                .willReturn(jsonResponse(200, """
                        [{"tag_name":"v4.0.0","name":"n","prerelease":false,"draft":false}]
                        """)));

        GithubReleaseLatestSource source =
                new GithubReleaseLatestSource("http://localhost:8089", Optional.empty(), SEMVER_PARSER);

        assertEquals("4.0.0", source.version().value());
    }

    @Test
    void read_followsAnAbsoluteSameOriginLocationHeader() {
        wireMockServer.stubFor(get(urlPathEqualTo("/releases"))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", "http://localhost:8089/relocated-absolute/releases")));
        wireMockServer.stubFor(get(urlPathEqualTo("/relocated-absolute/releases"))
                .willReturn(jsonResponse(200, """
                        [{"tag_name":"v5.0.0","name":"n","prerelease":false,"draft":false}]
                        """)));

        GithubReleaseLatestSource source =
                new GithubReleaseLatestSource("http://localhost:8089", Optional.empty(), SEMVER_PARSER);

        assertEquals("5.0.0", source.version().value());
    }

    @Test
    void read_retainsAuthorizationHeader_onInitialRequest_andOnSameOriginRedirectedRequest() {
        wireMockServer.stubFor(get(urlPathEqualTo("/releases"))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", "/relocated-same-origin/releases")));
        wireMockServer.stubFor(get(urlPathEqualTo("/relocated-same-origin/releases"))
                .willReturn(jsonResponse(200, """
                        [{"tag_name":"v6.0.0","name":"n","prerelease":false,"draft":false}]
                        """)));

        GithubReleaseLatestSource source =
                new GithubReleaseLatestSource("http://localhost:8089", Optional.of("test-token"), SEMVER_PARSER);

        source.version();

        wireMockServer.verify(getRequestedFor(urlPathEqualTo("/releases"))
                .withHeader("Authorization", equalTo("Bearer test-token")));
        wireMockServer.verify(getRequestedFor(urlPathEqualTo("/relocated-same-origin/releases"))
                .withHeader("Authorization", equalTo("Bearer test-token")));
    }

    @Test
    void read_stripsAuthorizationHeader_onCrossOriginRedirect_becauseEffectivePortChanges() {
        wireMockServer.stubFor(get(urlPathEqualTo("/releases"))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", "http://localhost:8091/releases")));
        crossOriginWireMockServer.stubFor(get(urlPathEqualTo("/releases"))
                .willReturn(jsonResponse(200, """
                        [{"tag_name":"v7.0.0","name":"n","prerelease":false,"draft":false}]
                        """)));

        GithubReleaseLatestSource source =
                new GithubReleaseLatestSource("http://localhost:8089", Optional.of("test-token"), SEMVER_PARSER);

        VersionValue result = source.version();

        assertEquals("7.0.0", result.value(), "the cross-origin redirect must still be followed");
        wireMockServer.verify(getRequestedFor(urlPathEqualTo("/releases"))
                .withHeader("Authorization", equalTo("Bearer test-token")));
        crossOriginWireMockServer.verify(getRequestedFor(urlPathEqualTo("/releases"))
                .withHeader("Authorization", absent()));
    }

    /**
     * ADR-0029: an HTTPS-to-HTTP downgrade must be refused before the HTTP target is ever
     * contacted. The JVM default trust store is temporarily pointed at the committed WireMock
     * self-signed cert (same fixture as {@code HttpCurrentSourceTlsIT}) so the INITIAL HTTPS leg
     * completes cleanly — proving the failure below is the downgrade refusal, not an unrelated TLS
     * trust error.
     */
    @Test
    void read_refusesHttpsToHttpDowngrade_beforeContactingTheHttpTarget() throws Exception {
        String originalTrustStore = System.getProperty("javax.net.ssl.trustStore");
        String originalTrustStorePassword = System.getProperty("javax.net.ssl.trustStorePassword");
        String originalTrustStoreType = System.getProperty("javax.net.ssl.trustStoreType");
        try {
            String keystorePath = Path.of(GithubReleaseLatestSourceIT.class.getClassLoader()
                    .getResource(TLS_KEYSTORE_RESOURCE).toURI()).toString();
            System.setProperty("javax.net.ssl.trustStore", keystorePath);
            System.setProperty("javax.net.ssl.trustStorePassword", TLS_KEYSTORE_PASSWORD);
            System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");

            httpsWireMockServer.stubFor(get(urlPathEqualTo("/releases"))
                    .willReturn(aResponse()
                            .withStatus(301)
                            .withHeader("Location", "http://localhost:8089/downgraded/releases")));
            wireMockServer.stubFor(get(urlPathEqualTo("/downgraded/releases"))
                    .willReturn(jsonResponse(200, """
                            [{"tag_name":"v8.0.0","name":"n","prerelease":false,"draft":false}]
                            """)));

            String httpsBaseUrl = "https://localhost:" + httpsWireMockServer.httpsPort();
            GithubReleaseLatestSource source =
                    new GithubReleaseLatestSource(httpsBaseUrl, Optional.empty(), SEMVER_PARSER);

            assertThrows(RuntimeException.class, source::version,
                    "an HTTPS response redirecting to plain HTTP must be refused, not followed");
            wireMockServer.verify(0, getRequestedFor(urlPathEqualTo("/downgraded/releases")));
        } finally {
            restoreProperty("javax.net.ssl.trustStore", originalTrustStore);
            restoreProperty("javax.net.ssl.trustStorePassword", originalTrustStorePassword);
            restoreProperty("javax.net.ssl.trustStoreType", originalTrustStoreType);
        }
    }

    @Test
    void read_aRedirectLoop_terminatesAsASourceReadFailure_withinBoundedTime() {
        wireMockServer.stubFor(get(urlPathEqualTo("/loop"))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", "/loop")));

        GithubReleaseLatestSource source =
                new GithubReleaseLatestSource("http://localhost:8089/loop", Optional.empty(), SEMVER_PARSER);

        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> assertThrows(RuntimeException.class,
                source::version,
                "a redirect loop must fail the read like any other unresolvable upstream, not hang"));
    }

    @Test
    void read_calledTwice_traversesAPermanentRedirectBothTimes_provingNoCaching() {
        wireMockServer.stubFor(get(urlPathEqualTo("/releases"))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", "/relocated-not-cached/releases")));
        wireMockServer.stubFor(get(urlPathEqualTo("/relocated-not-cached/releases"))
                .willReturn(jsonResponse(200, """
                        [{"tag_name":"v9.0.0","name":"n","prerelease":false,"draft":false}]
                        """)));

        GithubReleaseLatestSource source =
                new GithubReleaseLatestSource("http://localhost:8089", Optional.empty(), SEMVER_PARSER);

        source.version();
        source.version();

        wireMockServer.verify(2, getRequestedFor(urlPathEqualTo("/releases")));
        wireMockServer.verify(2, getRequestedFor(urlPathEqualTo("/relocated-not-cached/releases")));
    }

    @Test
    void read_finalNon2xxResponse_afterASupportedRedirect_stillSurfacesAsTheExistingFailure() {
        wireMockServer.stubFor(get(urlPathEqualTo("/releases"))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", "/relocated-failing/releases")));
        wireMockServer.stubFor(get(urlPathEqualTo("/relocated-failing/releases"))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        GithubReleaseLatestSource source =
                new GithubReleaseLatestSource("http://localhost:8089", Optional.empty(), SEMVER_PARSER);

        RuntimeException thrown = assertThrows(RuntimeException.class, source::version,
                "the intermediate 301 must not itself be reported as a failure — only the final "
                        + "non-2xx response should surface, via the existing exception mapping");
        assertInstanceOf(VersionFetchException.class, thrown);
    }

    private static void restoreProperty(String key, String originalValue) {
        if (originalValue == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, originalValue);
        }
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder jsonResponse(int status, String body) {
        return aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body);
    }
}
