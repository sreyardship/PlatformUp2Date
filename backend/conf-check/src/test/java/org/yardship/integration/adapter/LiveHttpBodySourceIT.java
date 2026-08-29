package org.yardship.integration.adapter;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.yardship.confcheck.adapter.LiveHttpBodySource;
import org.yardship.confcheck.port.BodySource;

import java.io.InputStream;
import java.time.Duration;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.stream.Stream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Integration tests for the real {@link LiveHttpBodySource} adapter against a standalone WireMock
 * server, mirroring the style of the backend's {@code HttpRegexLatestSourceIT}. Uses port 8090 (the
 * backend suite already claims 8089) so both suites can run concurrently without a port clash.
 */
class LiveHttpBodySourceIT {

    private static final String KEYSTORE_RESOURCE = "tls/wiremock-localhost.p12";
    private static final String KEYSTORE_PASSWORD = "password";

    static WireMockServer wireMockServer;
    static WireMockServer httpsWireMockServer;

    @BeforeAll
    static void startWireMock() throws Exception {
        wireMockServer = new WireMockServer(options().port(8090));
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

    @Test
    void twoHundredResponse_returnsBodyVerbatim() {
        wireMockServer.stubFor(get(urlPathEqualTo("/body"))
                .willReturn(aResponse().withStatus(200).withBody("Version: 1.2.3")));

        LiveHttpBodySource source = new LiveHttpBodySource("http://localhost:8090/body");

        assertEquals("Version: 1.2.3", source.body());
    }

    @Test
    void threeHundredOneRedirect_returnsFinalBodyVerbatim() {
        String finalBody = "<html>final body</html>\n";
        wireMockServer.stubFor(get(urlPathEqualTo("/redirected-body"))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", "/final-body")));
        wireMockServer.stubFor(get(urlPathEqualTo("/final-body"))
                .willReturn(aResponse().withStatus(200).withBody(finalBody)));

        LiveHttpBodySource source = new LiveHttpBodySource("http://localhost:8090/redirected-body");

        assertEquals(finalBody, source.body());
    }

    @ParameterizedTest(name = "status {0} with {1} Location")
    @MethodSource("supportedGetRedirects")
    void supportedGetRedirects_returnFinalBodyVerbatim_forRelativeAndAbsoluteLocations(
            int status, String locationKind) {
        String finalBody = "final body for status " + status;
        String finalPath = "/final-body-" + status;
        String finalQuery = "?channel=stable";
        String finalLocation = finalPath + finalQuery;
        String location = "relative".equals(locationKind)
                ? finalLocation
                : "http://localhost:8090" + finalLocation;
        wireMockServer.stubFor(get(urlPathEqualTo("/redirected-body-" + status))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Location", location)));
        wireMockServer.stubFor(get(urlEqualTo(finalPath + finalQuery))
                .willReturn(aResponse().withStatus(200).withBody(finalBody)));

        LiveHttpBodySource source = new LiveHttpBodySource(
                "http://localhost:8090/redirected-body-" + status);

        assertEquals(finalBody, source.body());
    }

    private static Stream<Arguments> supportedGetRedirects() {
        return Stream.of(301, 302, 303, 307, 308)
                .flatMap(status -> Stream.of(
                        Arguments.of(status, "relative"),
                        Arguments.of(status, "absolute")));
    }

    @Test
    void httpsToHttpRedirect_throwsBodyFetchException_withoutContactingTarget() throws Exception {
        String targetPath = "/downgrade-target";
        httpsWireMockServer.stubFor(get(urlEqualTo("/https-source"))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", "http://localhost:8090" + targetPath)));
        wireMockServer.stubFor(get(urlEqualTo(targetPath))
                .willReturn(aResponse().withStatus(200).withBody("unsafe final body")));

        SSLContext previous = SSLContext.getDefault();
        try {
            SSLContext.setDefault(trustedWireMockContext());
            LiveHttpBodySource source = new LiveHttpBodySource(
                    "https://localhost:" + httpsWireMockServer.httpsPort() + "/https-source");

            assertThrows(BodySource.BodyFetchException.class, source::body,
                    "an HTTPS-to-HTTP redirect must be rejected as a body-fetch failure");
        } finally {
            SSLContext.setDefault(previous);
        }

        httpsWireMockServer.verify(1, getRequestedFor(urlEqualTo("/https-source")));
        wireMockServer.verify(0, getRequestedFor(urlEqualTo(targetPath)));
    }

    @Test
    void redirectLoop_terminatesAsBodyFetchException() {
        wireMockServer.stubFor(get(urlEqualTo("/redirect-loop"))
                .willReturn(aResponse()
                        .withStatus(302)
                        .withHeader("Location", "/redirect-loop")));

        LiveHttpBodySource source = new LiveHttpBodySource("http://localhost:8090/redirect-loop");

        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertThrows(BodySource.BodyFetchException.class, source::body,
                        "a redirect loop must terminate as a body-fetch failure"));
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

    private static SSLContext trustedWireMockContext() throws Exception {
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = LiveHttpBodySourceIT.class.getClassLoader()
                .getResourceAsStream(KEYSTORE_RESOURCE)) {
            trustStore.load(Objects.requireNonNull(in), KEYSTORE_PASSWORD.toCharArray());
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
                Objects.requireNonNull(LiveHttpBodySourceIT.class.getClassLoader().getResource(resource)).toURI())
                .toString();
    }
}
