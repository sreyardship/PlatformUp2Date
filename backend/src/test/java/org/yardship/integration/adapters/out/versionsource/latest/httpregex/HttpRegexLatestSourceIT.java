package org.yardship.integration.adapters.out.versionsource.latest.httpregex;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yardship.adapters.out.versionsource.VersionFetchException;
import org.yardship.adapters.out.versionsource.latest.httpregex.HttpRegexLatestSourceFactory;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import org.yardship.core.domain.primitives.VersionValue;
import org.yardship.core.ports.out.LatestVersionSource;
import org.yardship.integration.adapters.out.versionsource.latest.LatestSourceFragment;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Base64;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the real {@code http-regex} latest-version kind, built end to end through
 * {@link HttpRegexLatestSourceFactory#create} against a standalone WireMock server on port 8089.
 * Plain JUnit — the kind needs no CDI.
 *
 * <p>Intentionally thin: the regex rule and the 2xx gate are covered by
 * {@code HttpRegexVersionExtractorTests} with fake responses; redirects, TLS and auth rendering
 * are proven once for the shared transport in {@code HttpTransportIT}. This class proves the kind's
 * own rule on the wire against the two real-world fixtures ADR-0017 was written for — Ubuntu's
 * plain-text {@code meta-release-lts} feed and OpenWRT's HTML releases listing, each under its own
 * calver format — and one hardened fragment end to end: {@code auth} and {@code ca-cert} reaching a
 * TLS endpoint behind a private CA, which this kind gained by moving onto the shared transport
 * (ADR-0040).
 */
class HttpRegexLatestSourceIT {

    private static final VersionParser CALVER_UBUNTU = new VersionParser(VersionScheme.CALVER, "YY.0M");
    private static final VersionParser CALVER_OPENWRT = new VersionParser(VersionScheme.CALVER, "YY.0M.MICRO");
    private static final VersionParser SEMVER_PARSER = new VersionParser(VersionScheme.SEMVER);

    private static final String TLS_KEYSTORE_RESOURCE = "tls/wiremock-localhost.p12";
    private static final String TLS_KEYSTORE_PASSWORD = "password";

    static WireMockServer wireMockServer;
    static WireMockServer httpsWireMockServer;
    static String httpsBaseUrl;

    private final HttpRegexLatestSourceFactory factory = new HttpRegexLatestSourceFactory();

    @BeforeAll
    static void startWireMock() throws Exception {
        wireMockServer = new WireMockServer(options().port(8089));
        wireMockServer.start();

        String keystorePath = Path.of(HttpRegexLatestSourceIT.class.getClassLoader()
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

    @Test
    void ubuntu_metaReleaseLts_returnsTheLatestLts_fromAPlainTextBody_endToEndThroughTheFactory() {
        wireMockServer.stubFor(get(urlPathEqualTo("/meta-release-lts"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "text/plain; charset=utf-8")
                        .withBody("""
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

        LatestVersionSource source = factory.create(
                fragment("http://localhost:8089/meta-release-lts", "Version: (\\d+\\.\\d+)"), CALVER_UBUNTU);

        assertEquals("24.04", source.version().value(),
                "must return the latest LTS (24.04) by picking the largest calver among all matches");
    }

    @Test
    void ubuntu_metaReleaseLts_acceptsABodyWithNoContentTypeHeader() {
        // The kind is content-type agnostic: WireMock omits the header here, a vendor may too.
        wireMockServer.stubFor(get(urlPathEqualTo("/meta-release-lts"))
                .willReturn(aResponse().withStatus(200).withBody("Version: 22.04\nVersion: 24.04")));

        LatestVersionSource source = factory.create(
                fragment("http://localhost:8089/meta-release-lts", "Version: (\\d+\\.\\d+)"), CALVER_UBUNTU);

        assertEquals("24.04", source.version().value());
    }

    // --- OpenWRT releases listing (HTML) ---------------------------------------------------------

    @Test
    void openWrt_releasesListing_returnsTheLargestCalverVersion_fromAnHtmlBody() {
        // 23.05.5 must win: its MICRO (5) exceeds 23.05's MICRO (0, absent defaults to 0).
        wireMockServer.stubFor(get(urlPathEqualTo("/releases/"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "text/html; charset=utf-8")
                        .withBody("""
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

        LatestVersionSource source = factory.create(
                fragment("http://localhost:8089/releases/", "href=\"(\\d+\\.\\d+(?:\\.\\d+)?)/\""), CALVER_OPENWRT);

        VersionValue result = source.version();

        assertEquals("23.05.5", result.value(), "23.05.5 (MICRO=5) must beat 23.05 (MICRO=0 default) and 22.03");
    }

    // --- failure surfaces --------------------------------------------------------------------------

    @Test
    void version_throwsAVersionFetchException_whenUpstreamReturnsNon2xx() {
        wireMockServer.stubFor(get(urlPathEqualTo("/versions"))
                .willReturn(aResponse().withStatus(404).withBody("Not Found")));

        LatestVersionSource source = factory.create(
                fragment("http://localhost:8089/versions", "(\\d+\\.\\d+\\.\\d+)"), SEMVER_PARSER);

        VersionFetchException ex = assertThrows(VersionFetchException.class, source::version);
        assertEquals(404, ex.status());
    }

    @Test
    void version_throws_whenNothingMatches_isolatingTheScrapeFailure() {
        wireMockServer.stubFor(get(urlPathEqualTo("/versions"))
                .willReturn(aResponse().withStatus(200).withBody("no version tokens here")));

        LatestVersionSource source = factory.create(
                fragment("http://localhost:8089/versions", "Version: (\\S+)"), SEMVER_PARSER);

        assertThrows(RuntimeException.class, source::version,
                "a body with no regex match must throw so the scrape loop can isolate this app");
    }

    // --- the hardening this kind gained from the shared transport (ADR-0040) ---------------------

    @Test
    void version_withAuthAndCaCert_scrapesATlsEndpointBehindAPrivateCa_endToEndThroughTheFactory(@TempDir Path dir)
            throws Exception {
        // WireMock's self-signed cert is not in the JVM trust bundle; only the configured ca-cert
        // lets the handshake complete. The listing is also behind basic auth: catch-all 401 first,
        // the credentialed 200 registered last ("last registered wins").
        Path pem = dir.resolve("ca.crt");
        Files.writeString(pem, wireMockCaPem());
        httpsWireMockServer.stubFor(get(urlPathEqualTo("/releases/")).willReturn(aResponse().withStatus(401)));
        httpsWireMockServer.stubFor(get(urlPathEqualTo("/releases/"))
                .withBasicAuth("vendor-bot", "s3cr3t")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody("<a href=\"1.9.0/\">1.9.0/</a>\n<a href=\"2.1.0/\">2.1.0/</a>")));

        LatestVersionSource source = factory.create(
                fragment(httpsBaseUrl + "/releases/", "href=\"(\\d+\\.\\d+\\.\\d+)/\"")
                        .basicAuth("vendor-bot", "s3cr3t")
                        .caCert(pem.toString()),
                SEMVER_PARSER);

        assertEquals("2.1.0", source.version().value());
        httpsWireMockServer.verify(getRequestedFor(urlPathEqualTo("/releases/")).withBasicAuth(
                new com.github.tomakehurst.wiremock.client.BasicCredentials("vendor-bot", "s3cr3t")));
    }

    @Test
    void version_withoutCaCert_cannotReachTheSameEndpoint_provingTheCaCertIsWhatMadeItWork() {
        httpsWireMockServer.stubFor(get(urlPathEqualTo("/releases/"))
                .willReturn(aResponse().withStatus(200).withBody("<a href=\"2.1.0/\">2.1.0/</a>")));

        LatestVersionSource source = factory.create(
                fragment(httpsBaseUrl + "/releases/", "href=\"(\\d+\\.\\d+\\.\\d+)/\""), SEMVER_PARSER);

        RuntimeException ex = assertThrows(RuntimeException.class, source::version);
        assertTrue(ex.getMessage().contains("Failed to fetch"), "a TLS trust failure; was: " + ex.getMessage());
    }

    // --- fixtures -----------------------------------------------------------------------------------

    private static LatestSourceFragment fragment(String url, String regex) {
        return LatestSourceFragment.ofType("http-regex").url(url).regex(regex);
    }

    private static String wireMockCaPem() throws Exception {
        KeyStore wireMockKeystore = KeyStore.getInstance("PKCS12");
        try (InputStream in = HttpRegexLatestSourceIT.class.getClassLoader().getResourceAsStream(TLS_KEYSTORE_RESOURCE)) {
            wireMockKeystore.load(in, TLS_KEYSTORE_PASSWORD.toCharArray());
        }
        Certificate ca = wireMockKeystore.getCertificate("wiremock");
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(ca.getEncoded());
        return "-----BEGIN CERTIFICATE-----\n" + base64 + "\n-----END CERTIFICATE-----\n";
    }
}
