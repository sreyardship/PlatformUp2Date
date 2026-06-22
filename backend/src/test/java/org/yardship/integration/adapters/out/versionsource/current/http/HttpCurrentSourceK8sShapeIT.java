package org.yardship.integration.adapters.out.versionsource.current.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yardship.adapters.out.versionsource.auth.FileBearerAuthFilter;
import org.yardship.adapters.out.versionsource.current.http.HttpCurrentVersionClient;
import org.yardship.adapters.out.versionsource.current.http.HttpCurrentVersionClientFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end tracer proving slices 01 ({@code token-file} bearer via {@link FileBearerAuthFilter}) and
 * 02 (per-scraper custom CA via a {@link KeyStore} truststore) COMPOSE into the original Kubernetes
 * {@code curl} shape:
 *
 * <pre>
 * curl --cacert /var/run/secrets/kubernetes.io/serviceaccount/ca.crt \
 *      -H "Authorization: Bearer $(cat /var/run/secrets/kubernetes.io/serviceaccount/token)" \
 *      https://kubernetes.default.svc/version
 * </pre>
 *
 * <p>A single request goes over HTTPS to a privately-signed WireMock that BOTH completes the pinned-CA
 * handshake (the response body is read) AND arrives carrying {@code Authorization: Bearer <file
 * contents>}. The per-capability edges are already covered by 01's
 * {@code HttpCurrentVersionClientFactoryIT} and 02's {@code HttpCurrentSourceTlsIT}; this keeps to the
 * one combined path.
 *
 * <p>HTTPS setup (committed PKCS12 keystore + in-memory truststore extraction) mirrors
 * {@link HttpCurrentSourceTlsIT}.
 */
@QuarkusTest
class HttpCurrentSourceK8sShapeIT {

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
        wireMockServer.stubFor(get(urlEqualTo("/version"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"version\":\"1.0.0\"}")));
    }

    @Test
    void build_withPinnedCaAndFileBearerToken_completesTheHttpsRequestCarryingTheFilesToken(
            @TempDir Path dir) throws Exception {
        Path tokenFile = dir.resolve("token");
        Files.writeString(tokenFile, "  k8s-sa-token\n");
        KeyStore trustStore = trustStoreHoldingWireMockCa();

        HttpCurrentVersionClient client = clientFactory.build(
                httpsBaseUrl + "/version",
                Optional.of(new FileBearerAuthFilter(tokenFile.toString())),
                Optional.of(trustStore));
        JsonNode body = client.getCurrentVersion();

        assertEquals("1.0.0", body.at("/version").textValue(),
                "the pinned-CA handshake must succeed so the response body is read");
        wireMockServer.verify(getRequestedFor(urlEqualTo("/version"))
                .withHeader("Authorization", equalTo("Bearer k8s-sa-token")));
    }

    private static KeyStore trustStoreHoldingWireMockCa() throws Exception {
        KeyStore wireMockKeystore = KeyStore.getInstance("PKCS12");
        try (InputStream in = HttpCurrentSourceK8sShapeIT.class.getClassLoader()
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
                HttpCurrentSourceK8sShapeIT.class.getClassLoader().getResource(resource).toURI()).toString();
    }
}
