package org.yardship.integration.adapters.out.versionsource.latest.httpregex;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yardship.adapters.out.versionsource.VersionFetchException;
import org.yardship.adapters.out.versionsource.latest.httpregex.HttpRegexLatestSource;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import org.yardship.core.domain.primitives.VersionValue;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

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

    private static final String TLS_KEYSTORE_RESOURCE = "tls/wiremock-localhost.p12";
    private static final String TLS_KEYSTORE_PASSWORD = "password";

    static WireMockServer wireMockServer;

    // HTTPS-only server (self-signed CN=localhost cert, same fixture as HttpCurrentSourceTlsIT /
    // HttpCurrentSourceRedirectIT) used only by the HTTPS-to-HTTP downgrade-refusal test.
    static WireMockServer httpsWireMockServer;
    static String httpsBaseUrl;

    @BeforeAll
    static void startWireMock() throws Exception {
        wireMockServer = new WireMockServer(options().port(8089));
        wireMockServer.start();

        String keystorePath = java.nio.file.Path.of(HttpRegexLatestSourceIT.class.getClassLoader()
                .getResource(TLS_KEYSTORE_RESOURCE).toURI()).toString();
        httpsWireMockServer = new WireMockServer(options()
                .httpDisabled(true)
                .dynamicHttpsPort()
                .keystorePath(keystorePath)
                .keystorePassword(TLS_KEYSTORE_PASSWORD)
                .keyManagerPassword(TLS_KEYSTORE_PASSWORD)
                .keystoreType("PKCS12"));
        httpsWireMockServer.start();
        httpsBaseUrl = "https://localhost:" + httpsWireMockServer.httpsPort();
    }

    @AfterAll
    static void stopWireMock() {
        wireMockServer.stop();
        httpsWireMockServer.stop();
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

    // --- ADR-0029 redirect parity (issue 03) ------------------------------------------------------
    // RED PHASE: HttpRegexLatestSource currently builds a plain HttpClient.newHttpClient() with the
    // JDK default Redirect.NEVER, so every 301/302/303/307/308 below is currently treated as a
    // non-2xx response (VersionFetchException) rather than being followed to its final body. These
    // tests are expected to fail until the implementer wires a redirect-following transport
    // (mapping InsecureRedirectException/TooManyRedirectsException-equivalents to
    // VersionFetchException) into this adapter.

    @Test
    void redirect301_toFinalHtmlBody_stillSelectsLargestParseableVersion() {
        wireMockServer.stubFor(get(urlPathEqualTo("/releases"))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", "/releases/final")
                        .withBody("this-is-not-html-and-has-no-version-token")));
        wireMockServer.stubFor(get(urlPathEqualTo("/releases/final"))
                .willReturn(htmlResponse(200, """
                        <!DOCTYPE html>
                        <html><body>
                        <pre>
                        <a href="21.02/">21.02/</a>
                        <a href="23.05.5/">23.05.5/</a>
                        </pre>
                        </body></html>
                        """)));

        HttpRegexLatestSource source = new HttpRegexLatestSource(
                "http://localhost:8089/releases",
                "href=\"(\\d+\\.\\d+(?:\\.\\d+)?)/\"",
                CALVER_OPENWRT);

        VersionValue result = source.version();

        assertEquals("23.05.5", result.value(),
                "a 301 must be followed to its final text/HTML body, and the largest parseable "
                        + "version from THAT body must win");
    }

    @ParameterizedTest(name = "a {0} redirect with a relative Location is followed to the final body")
    @ValueSource(ints = {301, 302, 303, 307, 308})
    void supportedRedirectStatus_relativeLocation_reachesFinalBody_andSelectsLargestVersion(int status) {
        wireMockServer.stubFor(get(urlPathEqualTo("/versions"))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Location", "/versions-final")
                        // Deliberately contains a version token: if this intermediate body were
                        // wrongly parsed instead of the final body, "0.0.1" would win instead of "2.0.0".
                        .withBody("0.0.1")));
        wireMockServer.stubFor(get(urlPathEqualTo("/versions-final"))
                .willReturn(plainTextResponse(200, "1.2.0\n2.0.0\n1.9.9")));

        HttpRegexLatestSource source = new HttpRegexLatestSource(
                "http://localhost:8089/versions",
                "(\\d+\\.\\d+\\.\\d+)",
                SEMVER_PARSER);

        VersionValue result = source.version();

        assertEquals("2.0.0", result.value(),
                "a " + status + " redirect must be followed to the final body before regex/parsing runs, "
                        + "not treated as a non-2xx failure and not parsed itself");
    }

    @Test
    void redirect_withAbsoluteLocation_reachesFinalBody() {
        wireMockServer.stubFor(get(urlPathEqualTo("/versions"))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", "http://localhost:8089/versions-final-abs")));
        wireMockServer.stubFor(get(urlPathEqualTo("/versions-final-abs"))
                .willReturn(plainTextResponse(200, "1.0.0\n3.0.0")));

        HttpRegexLatestSource source = new HttpRegexLatestSource(
                "http://localhost:8089/versions",
                "(\\d+\\.\\d+\\.\\d+)",
                SEMVER_PARSER);

        assertEquals("3.0.0", source.version().value(),
                "an absolute Location header must be followed just like a relative one");
    }

    @Test
    void redirect_httpsToHttp_isRefused_httpTargetNeverContacted() {
        httpsWireMockServer.stubFor(get(urlPathEqualTo("/versions"))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", "http://localhost:8089/downgraded")));

        HttpRegexLatestSource source = new HttpRegexLatestSource(
                httpsBaseUrl + "/versions",
                "(\\d+\\.\\d+\\.\\d+)",
                SEMVER_PARSER);

        // The initial HTTPS leg may itself fail (e.g. on trust, since this adapter's default HttpClient
        // doesn't trust the self-signed WireMock cert) depending on JVM default-SSLContext caching
        // order; what matters is that the HTTP downgrade target is never contacted either way.
        assertThrows(RuntimeException.class, source::version,
                "an HTTPS response redirecting to plain HTTP must never resolve to a version");
        wireMockServer.verify(0, getRequestedFor(urlPathEqualTo("/downgraded")));
    }

    @Test
    void redirectLoop_terminatesAsVersionFetchException_withinBoundedTime() {
        wireMockServer.stubFor(get(urlPathEqualTo("/loop-a"))
                .willReturn(aResponse().withStatus(301).withHeader("Location", "/loop-b")));
        wireMockServer.stubFor(get(urlPathEqualTo("/loop-b"))
                .willReturn(aResponse().withStatus(302).withHeader("Location", "/loop-a")));

        HttpRegexLatestSource source = new HttpRegexLatestSource(
                "http://localhost:8089/loop-a",
                "(\\d+\\.\\d+\\.\\d+)",
                SEMVER_PARSER);

        assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                assertThrows(VersionFetchException.class, source::version,
                        "a redirect loop must terminate as VersionFetchException, not hang or leak a "
                                + "raw transport exception"));
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
}
