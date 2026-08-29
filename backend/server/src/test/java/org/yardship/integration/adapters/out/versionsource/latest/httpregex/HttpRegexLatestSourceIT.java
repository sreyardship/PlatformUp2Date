package org.yardship.integration.adapters.out.versionsource.latest.httpregex;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.yardship.adapters.out.versionsource.VersionFetchException;
import org.yardship.adapters.out.versionsource.latest.httpregex.HttpRegexLatestSource;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import org.yardship.core.domain.primitives.VersionValue;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.InputStream;
import java.security.KeyStore;
import java.time.Duration;
import java.security.SecureRandom;
import java.util.stream.Stream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Integration tests for the real {@link HttpRegexLatestSource} adapter against a standalone
 * WireMock server on port 8089. The adapter fetches the URL with the JDK {@code java.net.http.HttpClient},
 * so no Quarkus/Arc context is needed — this is a plain JUnit integration test that exercises the
 * real HTTP fetch path end to end.
 *
 * <p>Two real-world fixtures are exercised:
 * <ol>
 *   <li><b>Ubuntu {@code meta-release-lts}</b> — plain text body; regex extracts version tokens,
 *       calver {@code YY.0M} parser picks the largest LTS release.</li>
 *   <li><b>OpenWRT releases directory listing</b> — HTML body; regex extracts version tokens from
 *       {@code href} attributes, calver {@code YY.0M.MICRO} parser picks the largest release
 *       (supports both 2-part and 3-part versions due to optional trailing MICRO token).</li>
 * </ol>
 *
 * <p><b>Production constructor:</b>
 * <pre>{@code
 * public HttpRegexLatestSource(String url, String regex, VersionParser parser) { ... }
 * }</pre>
 *
 * <p><b>Content-type agnostic:</b> Ubuntu serves {@code text/plain}, OpenWRT serves {@code text/html},
 * and WireMock may omit the header entirely. The fetch reads the body as a string regardless of
 * content type — these tests confirm no content-negotiation failure occurs.
 */
class HttpRegexLatestSourceIT {

    private static final VersionParser CALVER_UBUNTU  = new VersionParser(VersionScheme.CALVER, "YY.0M");
    private static final VersionParser CALVER_OPENWRT = new VersionParser(VersionScheme.CALVER, "YY.0M.MICRO");
    private static final VersionParser SEMVER_PARSER  = new VersionParser(VersionScheme.SEMVER);
    private static final String KEYSTORE_RESOURCE = "tls/wiremock-localhost.p12";
    private static final String KEYSTORE_PASSWORD = "password";

    static WireMockServer wireMockServer;
    static WireMockServer httpsWireMockServer;

    @BeforeAll
    static void startWireMock() throws Exception {
        wireMockServer = new WireMockServer(options().port(8089));
        wireMockServer.start();
        httpsWireMockServer = new WireMockServer(options()
                .httpDisabled(true)
                .dynamicHttpsPort()
                .keystorePath(resourcePath(KEYSTORE_RESOURCE))
                .keystorePassword(KEYSTORE_PASSWORD)
                .keyManagerPassword(KEYSTORE_PASSWORD)
                .keystoreType("PKCS12"));
        httpsWireMockServer.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (httpsWireMockServer != null) {
            httpsWireMockServer.stop();
        }
        wireMockServer.stop();
    }

    @BeforeEach
    void resetStubs() {
        wireMockServer.resetAll();
        httpsWireMockServer.resetAll();
    }

    // --- Ubuntu meta-release-lts (plain text) ------------------------------------------------

    /**
     * Fixture mirrors the real Ubuntu {@code meta-release-lts} plain-text feed. The body contains
     * multiple {@code Version: YY.MM} entries; the regex extracts all version tokens; the calver
     * {@code YY.0M} parser picks the largest — in this case {@code 24.04}.
     */
    @Test
    void ubuntu_metaReleaseLts_returnsLatestLts_fromPlainTextBody() {
        wireMockServer.stubFor(get(urlPathEqualTo("/meta-release-lts"))
                .willReturn(plainTextResponse(200, """
                        Dist: focal
                        Version: 20.04
                        LTS: True

                        Dist: jammy
                        Version: 22.04
                        LTS: True

                        Dist: noble
                        Version: 24.04
                        LTS: True
                        """)));

        HttpRegexLatestSource source = new HttpRegexLatestSource(
                "http://localhost:8089/meta-release-lts",
                "Version: (\\d+\\.\\d+)",
                CALVER_UBUNTU);

        VersionValue result = source.version();

        assertEquals("24.04", result.value(),
                "must return the latest LTS (24.04) by picking the largest calver among all matches");
    }

    /**
     * Same fixture but the content-type header is absent — confirms the client is truly
     * content-type-agnostic (no content negotiation failure).
     */
    @Test
    void ubuntu_metaReleaseLts_acceptsBodyWithNoContentTypeHeader() {
        wireMockServer.stubFor(get(urlPathEqualTo("/meta-release-lts"))
                .willReturn(aResponse()
                        .withStatus(200)
                        // Deliberately omit Content-Type header
                        .withBody("Version: 22.04\nVersion: 24.04")));

        HttpRegexLatestSource source = new HttpRegexLatestSource(
                "http://localhost:8089/meta-release-lts",
                "Version: (\\d+\\.\\d+)",
                CALVER_UBUNTU);

        VersionValue result = source.version();

        assertEquals("24.04", result.value());
    }

    // --- OpenWRT releases listing (HTML) ---------------------------------------------------------

    /**
     * Fixture mirrors an OpenWRT releases directory listing. The body is HTML; the regex extracts
     * version tokens from {@code href} attributes; the calver {@code YY.0M.MICRO} parser picks the
     * largest. {@code 23.05.5} must win because its MICRO (5) exceeds {@code 23.05}'s MICRO (0,
     * absent defaults to 0 per the calver trailing-optional rule).
     */
    @Test
    void openWrt_releasesListing_returnsLargestCalverVersion_fromHtmlBody() {
        wireMockServer.stubFor(get(urlPathEqualTo("/releases/"))
                .willReturn(htmlResponse(200, """
                        <!DOCTYPE html>
                        <html><body>
                        <pre>
                        <a href="21.02/">21.02/</a>
                        <a href="22.03/">22.03/</a>
                        <a href="23.05/">23.05/</a>
                        <a href="23.05.5/">23.05.5/</a>
                        </pre>
                        </body></html>
                        """)));

        HttpRegexLatestSource source = new HttpRegexLatestSource(
                "http://localhost:8089/releases/",
                "href=\"(\\d+\\.\\d+(?:\\.\\d+)?)/\"",
                CALVER_OPENWRT);

        VersionValue result = source.version();

        assertEquals("23.05.5", result.value(),
                "23.05.5 (MICRO=5) must beat 23.05 (MICRO=0 default) and 22.03");
    }

    @ParameterizedTest(name = "status {0} with {1} Location")
    @MethodSource("supportedGetRedirects")
    void redirectedReleasesListing_follows301_andPicksLargestVersionFromFinalHtmlBody(
            int status, String locationKind) {
        String finalQuery = "?channel=stable";
        String finalLocation = "/releases/" + finalQuery;
        String location = "relative".equals(locationKind)
                ? finalLocation
                : "http://localhost:8089" + finalLocation;
        wireMockServer.stubFor(get(urlPathEqualTo("/redirected-releases/"))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Location", location)));
        wireMockServer.stubFor(get(urlEqualTo(finalLocation))
                .willReturn(htmlResponse(200, """
                        <!DOCTYPE html>
                        <html><body>
                        <a href="23.05/">23.05/</a>
                        <a href="23.05.5/">23.05.5/</a>
                        <a href="24.10/">24.10/</a>
                        </body></html>
                        """)));

        HttpRegexLatestSource source = new HttpRegexLatestSource(
                "http://localhost:8089/redirected-releases/",
                "href=\"(\\d+\\.\\d+(?:\\.\\d+)?)/\"",
                CALVER_OPENWRT);

        assertEquals("24.10", source.version().value(),
                "supported GET redirects must be followed before selecting the largest parseable version");
    }

    private static Stream<Arguments> supportedGetRedirects() {
        return Stream.of(301, 302, 303, 307, 308)
                .flatMap(status -> Stream.of(
                        Arguments.of(status, "relative"),
                        Arguments.of(status, "absolute")));
    }

    @Test
    void repeatedCalls_retraversePermanentRedirect_withoutCachingTarget() {
        wireMockServer.stubFor(get(urlEqualTo("/permanent-releases"))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", "/permanent-releases-canonical")));
        wireMockServer.stubFor(get(urlEqualTo("/permanent-releases-canonical"))
                .willReturn(plainTextResponse(200, "1.2.0\n2.0.0")));

        HttpRegexLatestSource source = new HttpRegexLatestSource(
                "http://localhost:8089/permanent-releases",
                "(\\d+\\.\\d+\\.\\d+)",
                SEMVER_PARSER);

        assertEquals("2.0.0", source.version().value());
        assertEquals("2.0.0", source.version().value());
        wireMockServer.verify(2, getRequestedFor(urlEqualTo("/permanent-releases")));
        wireMockServer.verify(2, getRequestedFor(urlEqualTo("/permanent-releases-canonical")));
    }

    @Test
    void redirectSafety_refusesHttpsToHttp_beforeContactingTarget() throws Exception {
        String targetPath = "/downgrade-target";
        httpsWireMockServer.stubFor(get(urlEqualTo("/https-source"))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", "http://localhost:8089" + targetPath)));
        wireMockServer.stubFor(get(urlEqualTo(targetPath))
                .willReturn(plainTextResponse(200, "1.0.0")));

        SSLContext previous = SSLContext.getDefault();
        try {
            SSLContext.setDefault(trustedWireMockContext());
            HttpRegexLatestSource source = new HttpRegexLatestSource(
                    "https://localhost:" + httpsWireMockServer.httpsPort() + "/https-source",
                    "(\\d+\\.\\d+\\.\\d+)",
                    SEMVER_PARSER);

            assertThrows(VersionFetchException.class, source::version,
                    "an HTTPS-to-HTTP redirect must be refused as a version-fetch failure");
        } finally {
            SSLContext.setDefault(previous);
        }

        httpsWireMockServer.verify(1, getRequestedFor(urlEqualTo("/https-source")));
        wireMockServer.verify(0, getRequestedFor(urlEqualTo(targetPath)));
    }

    @Test
    void redirectChainBeyondMaximum_terminatesAsVersionFetchException() {
        for (int redirect = 0; redirect <= 10; redirect++) {
            wireMockServer.stubFor(get(urlEqualTo("/redirect-chain/" + redirect))
                    .willReturn(aResponse()
                            .withStatus(302)
                            .withHeader("Location", "/redirect-chain/" + (redirect + 1))));
        }

        HttpRegexLatestSource source = new HttpRegexLatestSource(
                "http://localhost:8089/redirect-chain/0",
                "(\\d+\\.\\d+\\.\\d+)",
                SEMVER_PARSER);

        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertThrows(VersionFetchException.class, source::version,
                        "a redirect chain beyond the limit must terminate as a version-fetch failure"));
    }

    @Test
    void redirectLoop_terminatesAsVersionFetchException() {
        wireMockServer.stubFor(get(urlEqualTo("/redirect-loop"))
                .willReturn(aResponse()
                        .withStatus(302)
                        .withHeader("Location", "/redirect-loop")));

        HttpRegexLatestSource source = new HttpRegexLatestSource(
                "http://localhost:8089/redirect-loop",
                "(\\d+\\.\\d+\\.\\d+)",
                SEMVER_PARSER);

        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertThrows(VersionFetchException.class, source::version,
                        "a redirect loop must terminate as a version-fetch failure"));
    }

    /**
     * OpenWRT fixture with semver: a hypothetical listing of semver-tagged releases. Confirms the
     * same adapter works when the app is configured with a semver parser rather than calver.
     */
    @Test
    void releasesListing_semverParser_picksLargestSemver() {
        wireMockServer.stubFor(get(urlPathEqualTo("/versions"))
                .willReturn(plainTextResponse(200, "1.2.0\n2.0.0\n1.9.9")));

        HttpRegexLatestSource source = new HttpRegexLatestSource(
                "http://localhost:8089/versions",
                "(\\d+\\.\\d+\\.\\d+)",
                SEMVER_PARSER);

        VersionValue result = source.version();

        assertEquals("2.0.0", result.value());
    }

    @Test
    void usesCaptureGroup1_notTheFullMatch() {
        // The full match includes the "release v" prefix; only capture group 1 is the version token.
        // If the source parsed the full match instead of group 1, "release v1.3.0" would be
        // unparseable and the source would not return "1.3.0".
        wireMockServer.stubFor(get(urlPathEqualTo("/versions"))
                .willReturn(plainTextResponse(200, "release v1.0.0\nrelease v1.3.0")));

        HttpRegexLatestSource source = new HttpRegexLatestSource(
                "http://localhost:8089/versions",
                "release v(\\d+\\.\\d+\\.\\d+)",
                SEMVER_PARSER);

        assertEquals("1.3.0", source.version().value(),
                "capture group 1 must be the parsed token, not the full regex match");
    }

    @Test
    void skipsUnparseableMatches_andPicksTheLargestSurvivor() {
        // The regex matches both a junk token and a valid one; the unparseable match must be skipped
        // rather than failing the whole read, leaving the valid version as the result.
        wireMockServer.stubFor(get(urlPathEqualTo("/versions"))
                .willReturn(plainTextResponse(200, "token: not-a-semver\ntoken: 2.0.0")));

        HttpRegexLatestSource source = new HttpRegexLatestSource(
                "http://localhost:8089/versions",
                "token: (\\S+)",
                SEMVER_PARSER);

        assertEquals("2.0.0", source.version().value(),
                "unparseable capture-group-1 tokens must be skipped, not fail the whole read");
    }

    // --- error / isolation cases -----------------------------------------------------------------

    @Test
    void noMatch_throws_isolatingTheScrapeFailure() {
        wireMockServer.stubFor(get(urlPathEqualTo("/versions"))
                .willReturn(plainTextResponse(200, "no version tokens here")));

        HttpRegexLatestSource source = new HttpRegexLatestSource(
                "http://localhost:8089/versions",
                "Version: (\\S+)",
                SEMVER_PARSER);

        assertThrows(RuntimeException.class, source::version,
                "a body with no regex match must throw so the scrape loop can isolate this app");
    }

    @Test
    void allMatchesUnparseable_throws_isolatingTheScrapeFailure() {
        wireMockServer.stubFor(get(urlPathEqualTo("/versions"))
                .willReturn(plainTextResponse(200, "Version: not-semver\nVersion: also-not-semver")));

        HttpRegexLatestSource source = new HttpRegexLatestSource(
                "http://localhost:8089/versions",
                "Version: (\\S+)",
                SEMVER_PARSER);

        assertThrows(RuntimeException.class, source::version,
                "if all matches are unparseable the source must throw, not return null/garbage");
    }

    @Test
    void nonSuccessHttpStatus_throws() {
        wireMockServer.stubFor(get(urlPathEqualTo("/versions"))
                .willReturn(aResponse().withStatus(404).withBody("Not Found")));

        HttpRegexLatestSource source = new HttpRegexLatestSource(
                "http://localhost:8089/versions",
                "(\\d+\\.\\d+\\.\\d+)",
                SEMVER_PARSER);

        assertThrows(RuntimeException.class, source::version,
                "a non-2xx HTTP response must throw (VersionFetchException), isolating this app's scrape");
    }

    @Test
    void connectionError_throwsVersionFetchException() {
        wireMockServer.stubFor(get(urlPathEqualTo("/connection-reset"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        HttpRegexLatestSource source = new HttpRegexLatestSource(
                "http://localhost:8089/connection-reset",
                "(\\d+\\.\\d+\\.\\d+)",
                SEMVER_PARSER);

        assertThrows(VersionFetchException.class, source::version,
                "a connection error must translate to a version-fetch failure, isolating this app's scrape");
    }

    // --- fixture helpers -------------------------------------------------------------------------

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder plainTextResponse(
            int status, String body) {
        return aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "text/plain; charset=utf-8")
                .withBody(body);
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder htmlResponse(
            int status, String body) {
        return aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "text/html; charset=utf-8")
                .withBody(body);
    }

    private static SSLContext trustedWireMockContext() throws Exception {
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = HttpRegexLatestSourceIT.class.getClassLoader()
                .getResourceAsStream(KEYSTORE_RESOURCE)) {
            trustStore.load(in, KEYSTORE_PASSWORD.toCharArray());
        }

        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trustStore);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers.getTrustManagers(), new SecureRandom());
        return context;
    }

    private static String resourcePath(String resource) throws Exception {
        return java.nio.file.Path.of(
                HttpRegexLatestSourceIT.class.getClassLoader().getResource(resource).toURI()).toString();
    }
}
