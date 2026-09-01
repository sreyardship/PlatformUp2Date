package org.yardship.integration.adapters.out.versionsource.current.http;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.VersionFetchException;
import org.yardship.adapters.out.versionsource.auth.BasicAuthFilter;
import org.yardship.adapters.out.versionsource.auth.BearerAuthFilter;
import org.yardship.adapters.out.versionsource.current.http.HttpCurrentSource;
import org.yardship.adapters.out.versionsource.current.http.HttpCurrentVersionClientFactory;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import org.yardship.core.domain.primitives.VersionValue;

import java.io.InputStream;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins ADR-0029 for the {@code http} current-version source: a configured current URL
 * that responds with a supported redirect must reach the final JSON body, resolve the existing JSON
 * Pointer, parse the version, and return it through {@link HttpCurrentSource} exactly as a direct
 * 2xx response does — while the {@code Authorization} header rendered by an
 * {@code HttpCurrentVersionClientFactory}-registered auth filter is retained only same-origin and
 * stripped cross-origin, and an HTTPS-to-HTTP downgrade is refused.
 *
 * <p>Drives the real adapter surface end to end: a client built by the injected
 * {@link HttpCurrentVersionClientFactory} handed to a real {@link HttpCurrentSource}, matching the
 * style of {@code HttpCurrentSourceIT} / {@code HttpCurrentSourceTlsIT}. Existing Basic/Bearer/
 * file-bearer/custom-CA/insecure-skip-tls-verify behavior is covered by those classes; this class
 * focuses on ADR-0029 redirect behavior.
 *
 * <p>The HTTP current adapter uses a redirect-following transport that preserves the configured
 * TLS policy while enforcing ADR-0029's credential and downgrade rules.
 */
@QuarkusTest
class HttpCurrentSourceRedirectIT {

    private static final VersionParser SEMVER_PARSER = new VersionParser(VersionScheme.SEMVER);
    private static final String TLS_KEYSTORE_RESOURCE = "tls/wiremock-localhost.p12";
    private static final String TLS_KEYSTORE_PASSWORD = "password";

    // Same-origin server: hosts both the initial and (same-origin) redirected requests.
    static WireMockServer wireMockServer;

    // A second, independently-hosted HTTP server standing in for a different origin (different port
    // than wireMockServer's 8089) — used only by the cross-origin-authorization test: a redirect
    // Location pointing here has a different effective port than the request that produced it, so
    // per ADR-0029 the Authorization header must NOT be forwarded to it.
    static WireMockServer crossOriginWireMockServer;

    // An HTTPS-only server (self-signed CN=localhost cert, same fixture as HttpCurrentSourceTlsIT)
    // used by the HTTPS-to-HTTP downgrade-refusal test and the within-custom-CA-origin redirect
    // tests below.
    static WireMockServer httpsWireMockServer;

    static String httpsBaseUrl;

    @Inject
    HttpCurrentVersionClientFactory clientFactory;

    @BeforeAll
    static void startWireMock() throws Exception {
        wireMockServer = new WireMockServer(options().port(8089));
        wireMockServer.start();

        crossOriginWireMockServer = new WireMockServer(options().port(8092));
        crossOriginWireMockServer.start();

        String keystorePath = Path.of(HttpCurrentSourceRedirectIT.class.getClassLoader()
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
        crossOriginWireMockServer.stop();
        httpsWireMockServer.stop();
    }

    @BeforeEach
    void resetStubs() {
        wireMockServer.resetAll();
        crossOriginWireMockServer.resetAll();
        httpsWireMockServer.resetAll();
    }

    // --- A 301 to a final JSON endpoint resolves through the real client and HttpCurrentSource,
    // with the final path asserted explicitly so the test cannot pass by deserializing the
    // intermediate response. ------------------------------------------------------------------

    @Test
    void read_follows301Redirect_toFinalJsonEndpoint_resolvesTheExpectedVersion() {
        // The intermediate 301's body is deliberately NOT valid version JSON: if an implementation
        // wrongly tried to extract a version from THIS response instead of following the redirect,
        // the JSON-pointer/parse step below would fail rather than silently returning "2.5.0".
        wireMockServer.stubFor(get(urlEqualTo("/current"))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", "/current-final")
                        .withBody("this-is-not-json-and-has-no-version-field")));
        wireMockServer.stubFor(get(urlEqualTo("/current-final"))
                .willReturn(jsonResponse(200, "{\"version\":\"2.5.0\"}")));

        HttpCurrentSource source = new HttpCurrentSource(
                clientFactory.build("http://localhost:8089/current", Optional.empty(), Optional.empty(), false),
                "/version", false, SEMVER_PARSER);

        VersionValue result = source.version();

        assertEquals("2.5.0", result.value());
        wireMockServer.verify(getRequestedFor(urlEqualTo("/current-final")));
    }

    // --- A same-origin authenticated redirect retains its Authorization header
    // intact (Basic and Bearer). ---------------------------------------------------------------

    @Test
    void read_followsSameOriginRedirect_withBasicAuth_retainsAuthorizationHeader_onFinalRequest() {
        wireMockServer.stubFor(get(urlEqualTo("/current"))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", "/current-final")));
        // Catch-all 401 registered FIRST, specific withBasicAuth 200 registered LAST — matching the
        // "last registered wins" WireMock stub-ordering convention used elsewhere in this suite.
        wireMockServer.stubFor(get(urlEqualTo("/current-final"))
                .willReturn(aResponse().withStatus(401)));
        wireMockServer.stubFor(get(urlEqualTo("/current-final"))
                .withBasicAuth("bob", "s3cr3t")
                .willReturn(jsonResponse(200, "{\"version\":\"3.0.0\"}")));

        HttpCurrentSource source = new HttpCurrentSource(
                clientFactory.build("http://localhost:8089/current",
                        Optional.of(new BasicAuthFilter("bob", "s3cr3t")), Optional.empty(), false),
                "/version", false, SEMVER_PARSER);

        VersionValue result = source.version();

        assertEquals("3.0.0", result.value());
        wireMockServer.verify(getRequestedFor(urlEqualTo("/current-final"))
                .withHeader("Authorization", matching("Basic .+")));
    }

    @Test
    void read_followsSameOriginRedirect_withBearerAuth_retainsAuthorizationHeader_onFinalRequest() {
        wireMockServer.stubFor(get(urlEqualTo("/current"))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", "/current-final")));
        wireMockServer.stubFor(get(urlEqualTo("/current-final"))
                .willReturn(aResponse().withStatus(401)));
        wireMockServer.stubFor(get(urlEqualTo("/current-final"))
                .withHeader("Authorization", equalTo("Bearer tok-123"))
                .willReturn(jsonResponse(200, "{\"version\":\"3.1.0\"}")));

        HttpCurrentSource source = new HttpCurrentSource(
                clientFactory.build("http://localhost:8089/current",
                        Optional.of(new BearerAuthFilter("tok-123")), Optional.empty(), false),
                "/version", false, SEMVER_PARSER);

        VersionValue result = source.version();

        assertEquals("3.1.0", result.value());
        wireMockServer.verify(getRequestedFor(urlEqualTo("/current-final"))
                .withHeader("Authorization", equalTo("Bearer tok-123")));
    }

    // --- A cross-origin authenticated redirect may reach a public target, but
    // target observes no Authorization header. ---------------------------------------------------

    @Test
    void read_followsCrossOriginRedirect_stripsAuthorizationHeader_beforeContactingTheTarget() {
        // Absolute Location pointing at a different port (8092 vs 8089) => different effective
        // origin per ADR-0029, even though the scheme/host are otherwise the same.
        wireMockServer.stubFor(get(urlEqualTo("/current"))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", "http://localhost:8092/current")));
        crossOriginWireMockServer.stubFor(get(urlEqualTo("/current"))
                .willReturn(jsonResponse(200, "{\"version\":\"4.0.0\"}")));

        HttpCurrentSource source = new HttpCurrentSource(
                clientFactory.build("http://localhost:8089/current",
                        Optional.of(new BearerAuthFilter("secret-token")), Optional.empty(), false),
                "/version", false, SEMVER_PARSER);

        VersionValue result = source.version();

        assertEquals("4.0.0", result.value(), "the cross-origin redirect must still be followed");
        wireMockServer.verify(getRequestedFor(urlEqualTo("/current"))
                .withHeader("Authorization", equalTo("Bearer secret-token")));
        crossOriginWireMockServer.verify(getRequestedFor(urlEqualTo("/current"))
                .withHeader("Authorization", absent()));
    }

    // --- HTTPS -> HTTP downgrade remains refused by redirect policy. ----------------------------

    @Test
    void read_refusesHttpsToHttpDowngrade_beforeContactingTheHttpTarget() throws Exception {
        // Uses the factory's own per-client truststore (rather than JVM-global
        // javax.net.ssl.trustStore system properties) so the initial HTTPS leg completes
        // deterministically and the failure asserted below is the downgrade refusal, not an
        // unrelated TLS trust error or JVM default-SSLContext caching artifact.
        KeyStore trustStore = trustStoreHoldingWireMockCa();
        httpsWireMockServer.stubFor(get(urlEqualTo("/current"))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", "http://localhost:8089/downgraded")));

        HttpCurrentSource source = new HttpCurrentSource(
                clientFactory.build(httpsBaseUrl + "/current", Optional.empty(), Optional.of(trustStore), false),
                "/version", false, SEMVER_PARSER);

        assertThrows(RuntimeException.class, source::version,
                "an HTTPS response redirecting to plain HTTP must be refused, not followed");
        wireMockServer.verify(0, getRequestedFor(urlEqualTo("/downgraded")));
    }

    // --- Redirect-transport / TLS-boundary gap: a redirect that stays WITHIN the custom-CA HTTPS
    // origin must still complete on both hops using the per-client trust configuration — this is
    // the default RedirectFollowingHttpGet client path. ------------------------------------------

    @Test
    void read_followsSameOriginHttpsRedirect_usingTheConfiguredTrustStore_onBothHops() throws Exception {
        KeyStore trustStore = trustStoreHoldingWireMockCa();
        httpsWireMockServer.stubFor(get(urlEqualTo("/current"))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", "/current-final")));
        httpsWireMockServer.stubFor(get(urlEqualTo("/current-final"))
                .willReturn(jsonResponse(200, "{\"version\":\"5.5.5\"}")));

        HttpCurrentSource source = new HttpCurrentSource(
                clientFactory.build(httpsBaseUrl + "/current", Optional.empty(), Optional.of(trustStore), false),
                "/version", false, SEMVER_PARSER);

        VersionValue result = source.version();

        assertEquals("5.5.5", result.value(),
                "the redirected (second-hop) HTTPS request must also trust the configured CA, not "
                        + "just the initial request");
    }

    @Test
    void read_followsSameOriginHttpsRedirect_withInsecureSkipTlsVerify_onBothHops() {
        httpsWireMockServer.stubFor(get(urlEqualTo("/current"))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", "/current-final")));
        httpsWireMockServer.stubFor(get(urlEqualTo("/current-final"))
                .willReturn(jsonResponse(200, "{\"version\":\"6.6.6\"}")));

        HttpCurrentSource source = new HttpCurrentSource(
                clientFactory.build(httpsBaseUrl + "/current", Optional.empty(), Optional.empty(), true),
                "/version", false, SEMVER_PARSER);

        VersionValue result = source.version();

        assertEquals("6.6.6", result.value(),
                "insecure-skip-tls-verify must apply to the redirected (second-hop) request too, "
                        + "not just the initial one");
    }

    // --- A final non-2xx response after a redirect still throws through
    // VersionResponseExceptionMapper and remains an isolated source failure. --------------------

    @Test
    void read_finalNon2xxResponse_afterASupportedRedirect_stillThrowsThroughTheExceptionMapper() {
        wireMockServer.stubFor(get(urlEqualTo("/current"))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", "/current-final")));
        wireMockServer.stubFor(get(urlEqualTo("/current-final"))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        HttpCurrentSource source = new HttpCurrentSource(
                clientFactory.build("http://localhost:8089/current", Optional.empty(), Optional.empty(), false),
                "/version", false, SEMVER_PARSER);

        RuntimeException thrown = assertThrows(RuntimeException.class, source::version,
                "the intermediate 301 must not itself be reported as a failure — only the final "
                        + "non-2xx response should surface, via the existing exception mapping");
        assertInstanceOf(VersionFetchException.class, thrown);
    }

    private static KeyStore trustStoreHoldingWireMockCa() throws Exception {
        KeyStore wireMockKeystore = KeyStore.getInstance("PKCS12");
        try (InputStream in = HttpCurrentSourceRedirectIT.class.getClassLoader()
                .getResourceAsStream(TLS_KEYSTORE_RESOURCE)) {
            wireMockKeystore.load(in, TLS_KEYSTORE_PASSWORD.toCharArray());
        }
        Certificate caCert = wireMockKeystore.getCertificate("wiremock");

        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        trustStore.setCertificateEntry("wiremock-ca", caCert);
        return trustStore;
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder jsonResponse(int status, String body) {
        return aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body);
    }
}
