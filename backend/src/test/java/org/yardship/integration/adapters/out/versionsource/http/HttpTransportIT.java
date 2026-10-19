package org.yardship.integration.adapters.out.versionsource.http;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import jakarta.ws.rs.client.ClientRequestFilter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yardship.adapters.out.versionsource.auth.BasicAuthFilter;
import org.yardship.adapters.out.versionsource.auth.BearerAuthFilter;
import org.yardship.adapters.out.versionsource.auth.FileBearerAuthFilter;
import org.yardship.adapters.out.versionsource.http.HttpGetResponse;
import org.yardship.adapters.out.versionsource.http.HttpTransport;
import org.yardship.adapters.out.versionsource.http.HttpTransportConfig;
import org.yardship.adapters.out.versionsource.http.InsecureRedirectException;
import org.yardship.adapters.out.versionsource.http.TooManyRedirectsException;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Base64;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The one place the shared {@link HttpTransport} is proven against real servers: redirects and the
 * ADR-0029 credential-origin and downgrade rules, auth rendering (including
 * {@code FileBearerAuthFilter}'s per-request re-read), per-client TLS trust on every hop, the
 * once-only retry on a connection-level failure, and the follow-up GET at a URI learned from an
 * earlier response. Every HTTP kind on both legs — {@code http-json}, {@code http-header},
 * {@code http-prometheus}, {@code github-release}, {@code http-regex}, {@code oci-registry} — fetches
 * through this class, so none of this is re-proven per kind; each kind's own IT covers only its
 * extraction rule end to end through its factory.
 *
 * <p>Plain JUnit — the transport needs no CDI. Three WireMock servers: a same-origin HTTP server
 * on 8089, a second HTTP server on 8092 standing in for a different origin (a redirect
 * {@code Location} pointing there has a different effective port, so per ADR-0029 the
 * {@code Authorization} header must NOT follow), and an HTTPS-only server using the committed
 * self-signed {@code CN=localhost} keystore ({@code tls/wiremock-localhost.p12}, storepass
 * {@code password}, generated once via {@code keytool -genkeypair -alias wiremock -keyalg RSA
 * -dname CN=localhost -ext SAN=dns:localhost,ip:127.0.0.1 -validity 36500}) that is NOT in the JVM
 * default trust bundle.
 */
class HttpTransportIT {

    private static final String TLS_KEYSTORE_RESOURCE = "tls/wiremock-localhost.p12";
    private static final String TLS_KEYSTORE_PASSWORD = "password";

    static WireMockServer wireMockServer;
    static WireMockServer crossOriginWireMockServer;
    static WireMockServer httpsWireMockServer;
    static String httpsBaseUrl;

    @BeforeAll
    static void startWireMock() throws Exception {
        wireMockServer = new WireMockServer(options().port(8089));
        wireMockServer.start();

        crossOriginWireMockServer = new WireMockServer(options().port(8092));
        crossOriginWireMockServer.start();

        String keystorePath = Path.of(HttpTransportIT.class.getClassLoader()
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

    // --- the response: status, headers and body of the final hop, whatever the status ----------

    @Test
    void get_returnsTheFinalResponse_withStatusHeadersAndBody() {
        wireMockServer.stubFor(get(urlEqualTo("/current")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("x-jenkins", "2.568.2")
                .withBody("{\"version\":\"1.0.0\"}")));

        HttpGetResponse response = plain("http://localhost:8089/current").get();

        assertEquals(200, response.status());
        assertEquals(Optional.of("2.568.2"), response.firstHeader("X-Jenkins"));
        assertEquals("{\"version\":\"1.0.0\"}", response.body());
    }

    @Test
    void get_returnsANon2xxFinalResponse_asIs_takingNoPositionOnTheStatus() {
        // Which statuses are acceptable is each kind's extractor's decision: http-header reads a
        // 403's headers (ADR-0030), http-json and http-prometheus refuse it. The transport hands
        // the final response over either way.
        wireMockServer.stubFor(get(urlEqualTo("/")).willReturn(aResponse()
                .withStatus(403)
                .withHeader("x-jenkins", "2.568.2")
                .withBody("<html>Forbidden</html>")));

        HttpGetResponse response = plain("http://localhost:8089/").get();

        assertEquals(403, response.status());
        assertEquals(Optional.of("2.568.2"), response.firstHeader("x-jenkins"));
        assertEquals("<html>Forbidden</html>", response.body());
    }

    // --- redirects (ADR-0029) --------------------------------------------------------------------

    @Test
    void get_follows301Redirect_andReturnsTheFinalHopsResponse() {
        // The intermediate 301 carries a decoy body and header: if the transport wrongly returned
        // THIS response instead of following the redirect, both assertions below would fail.
        wireMockServer.stubFor(get(urlEqualTo("/current")).willReturn(aResponse()
                .withStatus(301)
                .withHeader("Location", "/current-final")
                .withHeader("x-decoy", "intermediate")
                .withBody("this-is-not-the-final-body")));
        wireMockServer.stubFor(get(urlEqualTo("/current-final")).willReturn(aResponse()
                .withStatus(200)
                .withBody("{\"version\":\"2.5.0\"}")));

        HttpGetResponse response = plain("http://localhost:8089/current").get();

        assertEquals(200, response.status());
        assertEquals("{\"version\":\"2.5.0\"}", response.body());
        assertEquals(Optional.empty(), response.firstHeader("x-decoy"));
        wireMockServer.verify(getRequestedFor(urlEqualTo("/current-final")));
    }

    @Test
    void get_followsASameOriginRedirect_retainingTheAuthorizationHeader_onTheFinalRequest() {
        wireMockServer.stubFor(get(urlEqualTo("/current")).willReturn(aResponse()
                .withStatus(301)
                .withHeader("Location", "/current-final")));
        // Catch-all 401 registered FIRST, specific bearer-matching 200 registered LAST — WireMock's
        // "last registered wins" ordering.
        wireMockServer.stubFor(get(urlEqualTo("/current-final")).willReturn(aResponse().withStatus(401)));
        wireMockServer.stubFor(get(urlEqualTo("/current-final"))
                .withHeader("Authorization", equalTo("Bearer tok-123"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        HttpGetResponse response = withAuth("http://localhost:8089/current", new BearerAuthFilter("tok-123")).get();

        assertEquals(200, response.status());
        wireMockServer.verify(getRequestedFor(urlEqualTo("/current-final"))
                .withHeader("Authorization", equalTo("Bearer tok-123")));
    }

    @Test
    void get_followsACrossOriginRedirect_strippingTheAuthorizationHeader_beforeContactingTheTarget() {
        // Absolute Location pointing at a different port (8092 vs 8089) => different effective
        // origin per ADR-0029, even though the scheme/host are otherwise the same.
        wireMockServer.stubFor(get(urlEqualTo("/current")).willReturn(aResponse()
                .withStatus(301)
                .withHeader("Location", "http://localhost:8092/current")));
        crossOriginWireMockServer.stubFor(get(urlEqualTo("/current")).willReturn(aResponse()
                .withStatus(200).withBody("public")));

        HttpGetResponse response =
                withAuth("http://localhost:8089/current", new BearerAuthFilter("secret-token")).get();

        assertEquals("public", response.body(), "the cross-origin redirect must still be followed");
        wireMockServer.verify(getRequestedFor(urlEqualTo("/current"))
                .withHeader("Authorization", equalTo("Bearer secret-token")));
        crossOriginWireMockServer.verify(getRequestedFor(urlEqualTo("/current"))
                .withHeader("Authorization", absent()));
    }

    @Test
    void get_refusesAnHttpsToHttpDowngrade_beforeContactingTheHttpTarget() throws Exception {
        // The per-client truststore makes the initial HTTPS leg complete deterministically, so the
        // failure asserted below is the downgrade refusal, not an unrelated TLS trust error.
        httpsWireMockServer.stubFor(get(urlEqualTo("/current")).willReturn(aResponse()
                .withStatus(301)
                .withHeader("Location", "http://localhost:8089/downgraded")));

        HttpTransport transport = new HttpTransport(
                httpsBaseUrl + "/current", Optional.empty(), Optional.of(trustStoreHoldingWireMockCa()), false);

        assertThrows(InsecureRedirectException.class, transport::get,
                "an HTTPS response redirecting to plain HTTP must be refused, not followed");
        wireMockServer.verify(0, getRequestedFor(urlEqualTo("/downgraded")));
    }

    @Test
    void get_failsFast_onARedirectLoop() {
        wireMockServer.stubFor(get(urlEqualTo("/a")).willReturn(aResponse()
                .withStatus(302).withHeader("Location", "/b")));
        wireMockServer.stubFor(get(urlEqualTo("/b")).willReturn(aResponse()
                .withStatus(302).withHeader("Location", "/a")));

        assertThrows(TooManyRedirectsException.class, plain("http://localhost:8089/a")::get);
    }

    @Test
    void get_reportsTheFinalHopsUri_soARelativeReferenceInItsHeadersResolvesAgainstIt() {
        // RFC 8288: a relative Link target is resolved against the URI of the response that carried
        // it — the canonical (post-redirect) endpoint, not the origin the caller configured. The
        // oci-registry pagination loop depends on this when page one is served via a redirect.
        wireMockServer.stubFor(get(urlEqualTo("/v2/repo/tags/list")).willReturn(aResponse()
                .withStatus(302)
                .withHeader("Location", "/v2/repo/canonical/tags/list")));
        wireMockServer.stubFor(get(urlEqualTo("/v2/repo/canonical/tags/list")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Link", "<list?n=2&last=1.1.0>; rel=\"next\"")));

        HttpGetResponse response = plain("http://localhost:8089/v2/repo/tags/list").get();

        assertEquals(URI.create("http://localhost:8089/v2/repo/canonical/tags/list"), response.uri());
        assertEquals(URI.create("http://localhost:8089/v2/repo/canonical/tags/list?n=2&last=1.1.0"),
                response.uri().resolve("list?n=2&last=1.1.0"));
    }

    // --- a follow-up GET at a learned URI, with an explicit credential ---------------------------

    @Test
    void get_atALearnedUri_withAnExplicitCredential_sendsThatCredential_notTheConfiguredOne() {
        // The oci-registry dance (ADR-0013) learns its token realm from a 401 and its next page
        // from a Link header, and mints the Bearer it then carries: it drives the transport by URI
        // with a per-call credential. The configured credential (here: none) does not leak onto
        // that call, and the configured URL is not what is fetched.
        wireMockServer.stubFor(get(urlEqualTo("/token")).willReturn(aResponse().withStatus(401)));
        wireMockServer.stubFor(get(urlEqualTo("/token"))
                .withBasicAuth("user", "s3cr3t")
                .willReturn(aResponse().withStatus(200).withBody("{\"token\":\"minted\"}")));

        HttpGetResponse response = plain("http://localhost:8089/v2/repo/tags/list")
                .get(URI.create("http://localhost:8089/token"), Optional.of(new BasicAuthFilter("user", "s3cr3t")));

        assertEquals(200, response.status());
        assertEquals("{\"token\":\"minted\"}", response.body());
        wireMockServer.verify(0, getRequestedFor(urlEqualTo("/v2/repo/tags/list")));
    }

    @Test
    void get_atALearnedUri_withoutACredential_sendsNoAuthorizationHeader_evenWhenOneIsConfigured() {
        // The anonymous probe of the oci dance must stay anonymous: the realm credential is for the
        // realm, never the resource (ADR-0013).
        wireMockServer.stubFor(get(urlEqualTo("/probe")).willReturn(aResponse().withStatus(401)));

        withAuth("http://localhost:8089/configured", new BearerAuthFilter("configured-token"))
                .get(URI.create("http://localhost:8089/probe"), Optional.empty());

        wireMockServer.verify(getRequestedFor(urlEqualTo("/probe")).withHeader("Authorization", absent()));
    }

    @Test
    void get_atALearnedUri_stillAppliesTheRedirectAndCredentialRules() {
        wireMockServer.stubFor(get(urlEqualTo("/learned")).willReturn(aResponse()
                .withStatus(301)
                .withHeader("Location", "http://localhost:8092/learned")));
        crossOriginWireMockServer.stubFor(get(urlEqualTo("/learned")).willReturn(aResponse()
                .withStatus(200).withBody("public")));

        HttpGetResponse response = plain("http://localhost:8089/configured")
                .get(URI.create("http://localhost:8089/learned"), Optional.of(new BearerAuthFilter("minted")));

        assertEquals("public", response.body());
        wireMockServer.verify(getRequestedFor(urlEqualTo("/learned"))
                .withHeader("Authorization", equalTo("Bearer minted")));
        crossOriginWireMockServer.verify(getRequestedFor(urlEqualTo("/learned"))
                .withHeader("Authorization", absent()));
    }

    // --- connection-level failures are retried once (RFC 9112 §9.3.1) -----------------------------

    @Test
    void get_retriesOnce_whenTheConnectionDropsOnTheFirstAttempt() {
        // The HTTP/1.1 keep-alive close race: the server closes a connection at the moment the
        // client sends on it. Every GET the transport makes is idempotent, so RFC 9112 §9.3.1 says
        // to retry it automatically. First request: connection torn down without a response;
        // second request: a clean 200.
        wireMockServer.stubFor(get(urlEqualTo("/flaky")).inScenario("drop")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE))
                .willSetStateTo("recovered"));
        wireMockServer.stubFor(get(urlEqualTo("/flaky")).inScenario("drop")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        assertEquals("ok", plain("http://localhost:8089/flaky").get().body(),
                "a single connection drop must be absorbed by the idempotent-GET retry");
    }

    @Test
    void get_retriesOnce_whenTheConnectionResetsOnTheFirstAttempt() {
        wireMockServer.stubFor(get(urlEqualTo("/flaky")).inScenario("reset")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))
                .willSetStateTo("recovered"));
        wireMockServer.stubFor(get(urlEqualTo("/flaky")).inScenario("reset")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        assertEquals("ok", plain("http://localhost:8089/flaky").get().body(),
                "a single connection reset must be absorbed by the idempotent-GET retry");
    }

    @Test
    void get_failsAfterASecondConnectionFailure_withExactlyOneRetry() {
        wireMockServer.stubFor(get(urlEqualTo("/dead")).willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));

        assertThrows(RuntimeException.class, plain("http://localhost:8089/dead")::get,
                "a persistently failing connection must surface as a scrape failure after one retry");
        wireMockServer.verify(2, getRequestedFor(urlEqualTo("/dead")));
    }

    @Test
    void get_doesNotRetry_anHttpErrorStatus() {
        // Only a transport-level (I/O) failure is retried. A 500 is an answer, not a failure to
        // get one; retrying it would double the load on an upstream that is already struggling.
        wireMockServer.stubFor(get(urlEqualTo("/error")).willReturn(aResponse().withStatus(500)));

        assertEquals(500, plain("http://localhost:8089/error").get().status());
        wireMockServer.verify(1, getRequestedFor(urlEqualTo("/error")));
    }

    // --- auth rendering ----------------------------------------------------------------------------

    @Test
    void get_withoutAnAuthFilter_sendsNoAuthorizationHeader() {
        // Security guardrail: an absent auth filter must never add an Authorization header — the
        // current leg hits our own deployment endpoints, where a stray token would be a
        // secret-exfiltration bug.
        wireMockServer.stubFor(get(urlEqualTo("/current")).willReturn(aResponse().withStatus(200)));

        plain("http://localhost:8089/current").get();

        wireMockServer.verify(getRequestedFor(urlEqualTo("/current")).withHeader("Authorization", absent()));
    }

    @Test
    void get_withBasicAuth_sendsTheRenderedAuthorizationHeader() {
        wireMockServer.stubFor(get(urlEqualTo("/current")).willReturn(aResponse().withStatus(401)));
        wireMockServer.stubFor(get(urlEqualTo("/current"))
                .withBasicAuth("harbor-bot", "s3cr3t")
                .willReturn(aResponse().withStatus(200)));

        HttpGetResponse response =
                withAuth("http://localhost:8089/current", new BasicAuthFilter("harbor-bot", "s3cr3t")).get();

        assertEquals(200, response.status());
        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("harbor-bot:s3cr3t".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        wireMockServer.verify(getRequestedFor(urlEqualTo("/current"))
                .withHeader("Authorization", equalTo(expected)));
    }

    @Test
    void get_withBearerAuth_sendsTheRenderedAuthorizationHeader() {
        wireMockServer.stubFor(get(urlEqualTo("/current")).willReturn(aResponse().withStatus(401)));
        wireMockServer.stubFor(get(urlEqualTo("/current"))
                .withHeader("Authorization", equalTo("Bearer gh-token"))
                .willReturn(aResponse().withStatus(200)));

        HttpGetResponse response = withAuth("http://localhost:8089/current", new BearerAuthFilter("gh-token")).get();

        assertEquals(200, response.status());
        wireMockServer.verify(getRequestedFor(urlEqualTo("/current"))
                .withHeader("Authorization", equalTo("Bearer gh-token")));
    }

    @Test
    void get_withAFileBearerAuthFilter_reReadsTheTokenFile_onEveryGet(@TempDir Path dir) throws Exception {
        Path tokenFile = dir.resolve("token");
        Files.writeString(tokenFile, "  first-tok\n");
        wireMockServer.stubFor(get(urlEqualTo("/current")).willReturn(aResponse().withStatus(200)));
        HttpTransport transport =
                withAuth("http://localhost:8089/current", new FileBearerAuthFilter(tokenFile.toString()));

        transport.get();
        Files.writeString(tokenFile, "rotated-tok\n");
        transport.get();

        wireMockServer.verify(1, getRequestedFor(urlEqualTo("/current"))
                .withHeader("Authorization", equalTo("Bearer first-tok")));
        wireMockServer.verify(1, getRequestedFor(urlEqualTo("/current"))
                .withHeader("Authorization", equalTo("Bearer rotated-tok")));
    }

    // --- TLS: per-client trust, replace-not-augment, applied on every hop ------------------------

    @Test
    void get_withTheCaInTheTrustStore_completesTheHandshake() throws Exception {
        httpsWireMockServer.stubFor(get(urlEqualTo("/current")).willReturn(aResponse().withStatus(200).withBody("ok")));

        HttpTransport transport = new HttpTransport(
                httpsBaseUrl + "/current", Optional.empty(), Optional.of(trustStoreHoldingWireMockCa()), false);

        assertEquals("ok", transport.get().body(),
                "a client trusting the WireMock CA must complete the TLS handshake and read the body");
    }

    @Test
    void get_withoutACustomTrustStore_failsTheHandshake_provingNoGlobalTrustLeak() {
        // Optional.empty() => JVM default trust bundle only. WireMock's self-signed CA is not in it,
        // so the handshake must be rejected. That this still fails in the same class as the success
        // cases proves they did NOT install a JVM-global truststore. Also pins that
        // insecure-skip-tls-verify never becomes the default: this call passes insecure=false.
        httpsWireMockServer.stubFor(get(urlEqualTo("/current")).willReturn(aResponse().withStatus(200)));

        HttpTransport transport = plain(httpsBaseUrl + "/current");

        assertThrows(RuntimeException.class, transport::get,
                "with no custom truststore the self-signed WireMock cert must fail the TLS handshake");
    }

    @Test
    void get_withInsecureSkipTlsVerify_andNoTrustStore_completesTheHandshake() {
        // Full curl -k semantics: the JVM does not trust this cert and the hostname need not match.
        httpsWireMockServer.stubFor(get(urlEqualTo("/current")).willReturn(aResponse().withStatus(200).withBody("ok")));

        HttpTransport transport = new HttpTransport(httpsBaseUrl + "/current", Optional.empty(), Optional.empty(), true);

        assertEquals("ok", transport.get().body());
    }

    @Test
    void get_appliesTheTrustStore_onTheRedirectedHopToo() throws Exception {
        httpsWireMockServer.stubFor(get(urlEqualTo("/current")).willReturn(aResponse()
                .withStatus(301).withHeader("Location", "/current-final")));
        httpsWireMockServer.stubFor(get(urlEqualTo("/current-final")).willReturn(aResponse()
                .withStatus(200).withBody("final")));

        HttpTransport transport = new HttpTransport(
                httpsBaseUrl + "/current", Optional.empty(), Optional.of(trustStoreHoldingWireMockCa()), false);

        assertEquals("final", transport.get().body(),
                "the redirected (second-hop) HTTPS request must also trust the configured CA, not just the first");
    }

    @Test
    void get_appliesInsecureSkipTlsVerify_onTheRedirectedHopToo() {
        httpsWireMockServer.stubFor(get(urlEqualTo("/current")).willReturn(aResponse()
                .withStatus(301).withHeader("Location", "/current-final")));
        httpsWireMockServer.stubFor(get(urlEqualTo("/current-final")).willReturn(aResponse()
                .withStatus(200).withBody("final")));

        HttpTransport transport = new HttpTransport(httpsBaseUrl + "/current", Optional.empty(), Optional.empty(), true);

        assertEquals("final", transport.get().body(),
                "insecure-skip-tls-verify must apply to the redirected (second-hop) request too");
    }

    // --- config → transport: the ca-cert path a real app fragment takes --------------------------

    @Test
    void from_aResolvedCaCertConfig_pinsTheCaOnTheBuiltTransport_endToEnd(@TempDir Path dir) throws Exception {
        Path pem = dir.resolve("ca.crt");
        Files.writeString(pem, wireMockCaPem());
        httpsWireMockServer.stubFor(get(urlEqualTo("/current")).willReturn(aResponse().withStatus(200).withBody("ok")));
        String url = httpsBaseUrl + "/current";

        HttpTransportConfig.Resolution resolution = HttpTransportConfig.forCurrentSource("http-json")
                .resolve(Optional.empty(), Optional.of(pem.toString()), false, url);
        assertFalse(resolution.failureMessage().isPresent(), () -> resolution.failureMessage().get());

        assertEquals("ok", HttpTransport.from(url, resolution).get().body(),
                "a transport built from a resolved ca-cert must trust exactly that CA");
    }

    @Test
    void from_aResolvedInsecureSkipTlsVerifyConfig_disablesVerificationOnTheBuiltTransport_endToEnd() {
        httpsWireMockServer.stubFor(get(urlEqualTo("/current")).willReturn(aResponse().withStatus(200).withBody("ok")));
        String url = httpsBaseUrl + "/current";

        HttpTransportConfig.Resolution resolution = HttpTransportConfig.forCurrentSource("http-header")
                .resolve(Optional.empty(), Optional.empty(), true, url);
        assertFalse(resolution.failureMessage().isPresent(), () -> resolution.failureMessage().get());

        assertEquals("ok", HttpTransport.from(url, resolution).get().body(),
                "insecure-skip-tls-verify: true must reach the built transport through the config path");
    }

    // --- helpers -------------------------------------------------------------------------------------

    private static HttpTransport plain(String url) {
        return new HttpTransport(url, Optional.empty(), Optional.empty(), false);
    }

    private static HttpTransport withAuth(String url, ClientRequestFilter authFilter) {
        return new HttpTransport(url, Optional.of(authFilter), Optional.empty(), false);
    }

    private static Certificate wireMockCa() throws Exception {
        KeyStore wireMockKeystore = KeyStore.getInstance("PKCS12");
        try (InputStream in = HttpTransportIT.class.getClassLoader().getResourceAsStream(TLS_KEYSTORE_RESOURCE)) {
            wireMockKeystore.load(in, TLS_KEYSTORE_PASSWORD.toCharArray());
        }
        return wireMockKeystore.getCertificate("wiremock");
    }

    private static KeyStore trustStoreHoldingWireMockCa() throws Exception {
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        trustStore.setCertificateEntry("wiremock-ca", wireMockCa());
        return trustStore;
    }

    private static String wireMockCaPem() throws Exception {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(wireMockCa().getEncoded());
        return "-----BEGIN CERTIFICATE-----\n" + base64 + "\n-----END CERTIFICATE-----\n";
    }
}
