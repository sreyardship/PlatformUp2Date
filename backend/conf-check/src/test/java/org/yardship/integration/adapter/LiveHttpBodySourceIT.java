package org.yardship.integration.adapter;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yardship.confcheck.adapter.LiveHttpBodySource;
import org.yardship.confcheck.port.BodySource;

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
 * Integration tests for the real {@link LiveHttpBodySource} adapter against a standalone WireMock
 * server, mirroring the style of the backend's {@code HttpRegexLatestSourceIT}. Uses port 8090 (the
 * backend suite already claims 8089) so both suites can run concurrently without a port clash.
 *
 * <p><b>RED PHASE (issue 03):</b> {@code LiveHttpBodySource} currently builds a plain
 * {@code HttpClient.newHttpClient()} with the JDK default {@code Redirect.NEVER}, so every redirect
 * test below is expected to fail until the implementer gives this module its OWN small
 * redirect-following transport (mirroring {@code RedirectFollowingHttpGet} in
 * {@code :backend:server}, but NOT imported from it — that would create a
 * conf-check -> server dependency, which is explicitly disallowed) and maps its failure modes to
 * {@link BodySource.BodyFetchException}.
 */
class LiveHttpBodySourceIT {

    private static final String TLS_KEYSTORE_RESOURCE = "tls/wiremock-localhost.p12";
    private static final String TLS_KEYSTORE_PASSWORD = "password";

    static WireMockServer wireMockServer;

    // HTTPS-only server (self-signed CN=localhost cert; keystore copied from
    // backend/server/src/test/resources/tls/wiremock-localhost.p12 into this module's own test
    // resources, since :backend:conf-check must not depend on :backend:server) used only by the
    // HTTPS-to-HTTP downgrade-refusal test.
    static WireMockServer httpsWireMockServer;
    static String httpsBaseUrl;

    @BeforeAll
    static void startWireMock() throws Exception {
        wireMockServer = new WireMockServer(options().port(8090));
        wireMockServer.start();

        String keystorePath = java.nio.file.Path.of(LiveHttpBodySourceIT.class.getClassLoader()
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

    @Test
    void twoHundredResponse_returnsBodyVerbatim() {
        wireMockServer.stubFor(get(urlPathEqualTo("/body"))
                .willReturn(aResponse().withStatus(200).withBody("Version: 1.2.3")));

        LiveHttpBodySource source = new LiveHttpBodySource("http://localhost:8090/body");

        assertEquals("Version: 1.2.3", source.body());
    }

    @Test
    void nonTwoXxResponse_throwsBodyFetchException() {
        wireMockServer.stubFor(get(urlPathEqualTo("/body"))
                .willReturn(aResponse().withStatus(404).withBody("Not Found")));

        LiveHttpBodySource source = new LiveHttpBodySource("http://localhost:8090/body");

        assertThrows(BodySource.BodyFetchException.class, source::body,
                "a non-2xx response must translate to a fetch failure, not the raw body");
    }

    @Test
    void connectionError_throwsBodyFetchException() {
        // Port 8091 has no WireMock server listening on it in this test — a real connection error.
        LiveHttpBodySource source = new LiveHttpBodySource("http://localhost:8091/unreachable");

        assertThrows(BodySource.BodyFetchException.class, source::body,
                "a connection error must translate to a fetch failure, not propagate a raw IOException");
    }

    // --- ADR-0029 redirect parity (issue 03) ------------------------------------------------------

    @Test
    void redirect301_returnsFinalBodyVerbatim() {
        wireMockServer.stubFor(get(urlPathEqualTo("/body"))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", "/body-final")
                        .withBody("this-must-not-be-returned")));
        wireMockServer.stubFor(get(urlPathEqualTo("/body-final"))
                .willReturn(aResponse().withStatus(200).withBody("Version: 9.9.9")));

        LiveHttpBodySource source = new LiveHttpBodySource("http://localhost:8090/body");

        assertEquals("Version: 9.9.9", source.body(),
                "a 301 must be followed and the FINAL body returned verbatim, not the intermediate one");
    }

    @ParameterizedTest(name = "a {0} redirect with a relative Location is followed to the final body")
    @ValueSource(ints = {301, 302, 303, 307, 308})
    void supportedRedirectStatus_relativeLocation_returnsFinalBodyVerbatim(int status) {
        wireMockServer.stubFor(get(urlPathEqualTo("/body"))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Location", "/body-final")));
        wireMockServer.stubFor(get(urlPathEqualTo("/body-final"))
                .willReturn(aResponse().withStatus(200).withBody("final-body-for-" + status)));

        LiveHttpBodySource source = new LiveHttpBodySource("http://localhost:8090/body");

        assertEquals("final-body-for-" + status, source.body(),
                "a " + status + " redirect must be followed to the final body, not treated as a "
                        + "non-2xx failure");
    }

    @Test
    void redirect_withAbsoluteLocation_returnsFinalBodyVerbatim() {
        wireMockServer.stubFor(get(urlPathEqualTo("/body"))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", "http://localhost:8090/body-final-abs")));
        wireMockServer.stubFor(get(urlPathEqualTo("/body-final-abs"))
                .willReturn(aResponse().withStatus(200).withBody("final-body-absolute")));

        LiveHttpBodySource source = new LiveHttpBodySource("http://localhost:8090/body");

        assertEquals("final-body-absolute", source.body(),
                "an absolute Location header must be followed just like a relative one");
    }

    @Test
    void redirect_httpsToHttp_isRefused_httpTargetNeverContacted() {
        httpsWireMockServer.stubFor(get(urlPathEqualTo("/body"))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", "http://localhost:8090/downgraded")));

        LiveHttpBodySource source = new LiveHttpBodySource(httpsBaseUrl + "/body");

        // The initial HTTPS leg may itself fail (e.g. on trust, since this adapter's default
        // HttpClient doesn't trust the self-signed WireMock cert) depending on JVM default-SSLContext
        // caching order; what matters is that the HTTP downgrade target is never contacted either way.
        assertThrows(BodySource.BodyFetchException.class, source::body,
                "an HTTPS response redirecting to plain HTTP must never resolve to a body");
        wireMockServer.verify(0, getRequestedFor(urlPathEqualTo("/downgraded")));
    }

    @Test
    void redirectLoop_terminatesAsBodyFetchException_withinBoundedTime() {
        wireMockServer.stubFor(get(urlPathEqualTo("/loop-a"))
                .willReturn(aResponse().withStatus(301).withHeader("Location", "/loop-b")));
        wireMockServer.stubFor(get(urlPathEqualTo("/loop-b"))
                .willReturn(aResponse().withStatus(302).withHeader("Location", "/loop-a")));

        LiveHttpBodySource source = new LiveHttpBodySource("http://localhost:8090/loop-a");

        assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                assertThrows(BodySource.BodyFetchException.class, source::body,
                        "a redirect loop must terminate as BodyFetchException, not hang or leak a "
                                + "raw transport exception"));
    }
}
