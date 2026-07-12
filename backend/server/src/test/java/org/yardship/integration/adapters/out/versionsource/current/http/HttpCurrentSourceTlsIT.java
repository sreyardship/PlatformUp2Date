package org.yardship.integration.adapters.out.versionsource.current.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.current.http.HttpCurrentVersionClient;
import org.yardship.adapters.out.versionsource.current.http.HttpCurrentVersionClientFactory;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * HTTPS integration test proving the {@code ca-cert} replace-semantics contract of
 * {@link HttpCurrentVersionClientFactory#build}. WireMock is started with TLS using a self-signed
 * {@code CN=localhost} certificate that is NOT in the JVM default trust bundle, so:
 *
 * <ul>
 *   <li>building the client with {@link Optional#empty()} for the truststore must FAIL the TLS
 *       handshake — this both proves "replace, not the global default" and, because it runs in the
 *       same class as the success case, proves no global {@code javax.net.ssl.trustStore} or shared
 *       TLS-registry default was set by the other test (no trust leak);</li>
 *   <li>building the client with a truststore that holds the WireMock CA must SUCCEED.</li>
 * </ul>
 *
 * <p>The WireMock keystore is a committed test resource ({@code tls/wiremock-localhost.p12}, storepass
 * {@code password}) generated once via
 * {@code keytool -genkeypair -alias wiremock -keyalg RSA -dname CN=localhost
 * -ext SAN=dns:localhost,ip:127.0.0.1 -validity 36500}. The same cert is extracted from that keystore
 * at runtime into the in-memory truststore for the success case, so the test needs no separate PEM.
 *
 * <p>RED PHASE: until {@code HttpCurrentVersionClientFactory.build(...)} registers the truststore via
 * {@code QuarkusRestClientBuilder.trustStore(...)}, the success case fails (the handshake is rejected
 * because the supplied truststore is ignored).
 */
@QuarkusTest
class HttpCurrentSourceTlsIT {

    private static final String KEYSTORE_RESOURCE = "tls/wiremock-localhost.p12";
    private static final String KEYSTORE_PASSWORD = "password";

    static WireMockServer wireMockServer;
    static String httpsBaseUrl;

    @Inject
    HttpCurrentVersionClientFactory clientFactory;

    @BeforeAll
    static void startWireMock() throws Exception {
        String keystorePath = resourcePath(KEYSTORE_RESOURCE);
        wireMockServer = new WireMockServer(options()
                .httpDisabled(true)
                .dynamicHttpsPort()
                .keystorePath(keystorePath)
                .keystorePassword(KEYSTORE_PASSWORD)
                .keyManagerPassword(KEYSTORE_PASSWORD)
                .keystoreType("PKCS12"));
        wireMockServer.start();
        httpsBaseUrl = "https://localhost:" + wireMockServer.httpsPort();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @BeforeEach
    void resetStubs() {
        wireMockServer.resetAll();
        wireMockServer.stubFor(get(urlEqualTo("/current"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"version\":\"1.0.0\"}")));
    }

    @Test
    void build_withTheWireMockCaInTheTrustStore_completesTheHttpsRequest() throws Exception {
        KeyStore trustStore = trustStoreHoldingWireMockCa();

        HttpCurrentVersionClient client =
                clientFactory.build(httpsBaseUrl + "/current", Optional.empty(), Optional.of(trustStore), false);
        JsonNode body = client.getCurrentVersion();

        assertEquals("1.0.0", body.at("/version").textValue(),
                "a client trusting the WireMock CA must complete the TLS handshake and read the body");
    }

    @Test
    void build_withoutACustomTrustStore_failsTheHandshake_provingNoGlobalTrustLeak() {
        // Optional.empty() => JVM default trust bundle only. WireMock's self-signed CA is not in it,
        // so the handshake must be rejected. That this still fails in the same class as the success
        // case proves the success case did NOT install a JVM-global truststore. Also pins that
        // insecure-skip-tls-verify never becomes the default: this call passes insecure=false.
        HttpCurrentVersionClient client =
                clientFactory.build(httpsBaseUrl + "/current", Optional.empty(), Optional.empty(), false);

        assertThrows(RuntimeException.class, client::getCurrentVersion,
                "with no custom truststore the self-signed WireMock cert must fail the TLS handshake");
    }

    @Test
    void build_withInsecureSkipTlsVerifyTrue_andNoTrustStore_completesTheHttpsRequest() {
        // Full curl -k semantics: no truststore configured (Optional.empty()) but
        // insecureSkipTlsVerify=true must still resolve the version against the self-signed WireMock
        // HTTPS endpoint — the JVM does not trust this cert and the hostname need not match.
        HttpCurrentVersionClient client =
                clientFactory.build(httpsBaseUrl + "/current", Optional.empty(), Optional.empty(), true);
        JsonNode body = client.getCurrentVersion();

        assertEquals("1.0.0", body.at("/version").textValue(),
                "insecure-skip-tls-verify=true must complete the handshake against an untrusted "
                        + "self-signed certificate with no custom truststore");
    }

    private static KeyStore trustStoreHoldingWireMockCa() throws Exception {
        KeyStore wireMockKeystore = KeyStore.getInstance("PKCS12");
        try (InputStream in = HttpCurrentSourceTlsIT.class.getClassLoader()
                .getResourceAsStream(KEYSTORE_RESOURCE)) {
            wireMockKeystore.load(in, KEYSTORE_PASSWORD.toCharArray());
        }
        Certificate caCert = wireMockKeystore.getCertificate("wiremock");

        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        trustStore.setCertificateEntry("wiremock-ca", caCert);
        return trustStore;
    }

    private static String resourcePath(String resource) throws Exception {
        return java.nio.file.Path.of(
                HttpCurrentSourceTlsIT.class.getClassLoader().getResource(resource).toURI()).toString();
    }
}
