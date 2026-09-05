package org.yardship.integration.adapters.out.versionsource.current.httpprometheus;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader.VersionSource.Auth;
import org.yardship.adapters.out.versionsource.current.httpprometheus.HttpPrometheusCurrentSourceFactory;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import org.yardship.core.ports.out.CurrentVersionSource;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Base64;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * HTTPS integration test proving that {@code ca-cert} pinning and {@code insecure-skip-tls-verify}
 * work for the {@code http-prometheus} current source EXACTLY as they do for {@code http-json} and
 * {@code http-header} — the evidence that this kind gets full transport parity through the
 * unchanged, shared {@code HttpTransportConfig} (ADR-0033's "Consequences" section). Modelled
 * directly on {@code HttpHeaderCurrentSourceTlsIT}, driven through
 * {@link HttpPrometheusCurrentSourceFactory#create} (the config-to-transport path a real app
 * config fragment exercises) rather than by constructing TLS inputs by hand.
 *
 * <p>WireMock is started with TLS using the same committed self-signed {@code CN=localhost}
 * certificate fixture ({@code tls/wiremock-localhost.p12}, storepass {@code password}) the
 * {@code http-json}/{@code http-header} TLS tests use — it is NOT in the JVM default trust bundle,
 * so a client with no custom trust configuration must fail the handshake, and one configured with
 * the matching {@code ca-cert} (or {@code insecure-skip-tls-verify: true}) must succeed.
 */
@QuarkusTest
class HttpPrometheusCurrentSourceTlsIT {

    private static final VersionParser SEMVER_PARSER = new VersionParser(VersionScheme.SEMVER);
    private static final String KEYSTORE_RESOURCE = "tls/wiremock-localhost.p12";
    private static final String KEYSTORE_PASSWORD = "password";
    private static final String METRIC = "blackbox_exporter_build_info";
    private static final String BODY =
            "blackbox_exporter_build_info{branch=\"HEAD\",version=\"0.25.0\"} 1\n";

    static WireMockServer wireMockServer;
    static String httpsBaseUrl;

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
        wireMockServer.stubFor(get(urlEqualTo("/metrics")).willReturn(
                aResponse().withStatus(200).withBody(BODY)));
    }

    @Test
    void create_withCaCertPinningTheWireMockCa_completesTheHandshake_andReadsTheMetric(@TempDir Path dir)
            throws Exception {
        Path pem = dir.resolve("ca.crt");
        Files.writeString(pem, wireMockCaPem());

        CurrentVersionSource source = new HttpPrometheusCurrentSourceFactory().create(
                sourceWithCaCert(httpsBaseUrl + "/metrics", METRIC, pem.toString()), SEMVER_PARSER);

        assertEquals("0.25.0", source.version().value(),
                "a client trusting the WireMock CA must complete the TLS handshake and read the metric");
    }

    @Test
    void create_withNoCustomTrustConfiguration_failsTheHandshake_provingNoTrustLeak() {
        // No ca-cert, no insecure-skip-tls-verify: the JVM default trust bundle stays in place and
        // does not include this self-signed cert, so the handshake must be rejected. Runs in the
        // same class as the success cases to prove neither installed a JVM-global truststore.
        CurrentVersionSource source = new HttpPrometheusCurrentSourceFactory().create(
                source(httpsBaseUrl + "/metrics", METRIC), SEMVER_PARSER);

        assertThrows(RuntimeException.class, source::version,
                "with no custom trust configuration the self-signed WireMock cert must fail the handshake");
    }

    @Test
    void create_withInsecureSkipTlsVerifyTrue_completesTheHandshake_andReadsTheMetric() {
        CurrentVersionSource source = new HttpPrometheusCurrentSourceFactory().create(
                sourceWithInsecureSkipTlsVerify(httpsBaseUrl + "/metrics", METRIC, true), SEMVER_PARSER);

        assertEquals("0.25.0", source.version().value(),
                "insecure-skip-tls-verify=true must complete the handshake against an untrusted "
                        + "self-signed certificate with no ca-cert configured");
    }

    private static String wireMockCaPem() throws Exception {
        KeyStore keystore = KeyStore.getInstance("PKCS12");
        try (InputStream in = HttpPrometheusCurrentSourceTlsIT.class.getClassLoader()
                .getResourceAsStream(KEYSTORE_RESOURCE)) {
            keystore.load(in, KEYSTORE_PASSWORD.toCharArray());
        }
        Certificate cert = keystore.getCertificate("wiremock");
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(cert.getEncoded());
        return "-----BEGIN CERTIFICATE-----\n" + base64 + "\n-----END CERTIFICATE-----\n";
    }

    private static String resourcePath(String resource) throws Exception {
        return Path.of(HttpPrometheusCurrentSourceTlsIT.class.getClassLoader().getResource(resource).toURI())
                .toString();
    }

    private static ApplicationConfigLoader.VersionSource source(String url, String metric) {
        return new FakeVersionSource(Optional.of(url), Optional.of(metric), Optional.empty(), Optional.empty());
    }

    private static ApplicationConfigLoader.VersionSource sourceWithCaCert(String url, String metric, String caCert) {
        return new FakeVersionSource(
                Optional.of(url), Optional.of(metric), Optional.of(caCert), Optional.empty());
    }

    private static ApplicationConfigLoader.VersionSource sourceWithInsecureSkipTlsVerify(
            String url, String metric, boolean insecureSkipTlsVerify) {
        return new FakeVersionSource(
                Optional.of(url), Optional.of(metric), Optional.empty(), Optional.of(insecureSkipTlsVerify));
    }

    /**
     * Fully implements {@link ApplicationConfigLoader.VersionSource}, defaulting every field not
     * exercised here to {@link Optional#empty()}. {@code type()} is fixed to
     * {@code "http-prometheus"}.
     */
    private static final class FakeVersionSource implements ApplicationConfigLoader.VersionSource {
        private final Optional<String> url;
        private final Optional<String> metric;
        private final Optional<String> caCert;
        private final Optional<Boolean> insecureSkipTlsVerify;

        FakeVersionSource(Optional<String> url, Optional<String> metric, Optional<String> caCert,
                Optional<Boolean> insecureSkipTlsVerify) {
            this.url = url;
            this.metric = metric;
            this.caCert = caCert;
            this.insecureSkipTlsVerify = insecureSkipTlsVerify;
        }

        @Override
        public Optional<String> type() {
            return Optional.of("http-prometheus");
        }

        @Override
        public Optional<String> url() {
            return url;
        }

        @Override
        public Optional<String> metric() {
            return metric;
        }

        @Override
        public Optional<String> versionLabel() {
            return Optional.empty();
        }

        @Override
        public Optional<String> versionHeader() {
            return Optional.empty();
        }

        @Override
        public Optional<String> regex() {
            return Optional.empty();
        }

        @Override
        public Optional<Boolean> stripPrerelease() {
            return Optional.empty();
        }

        @Override
        public Optional<Auth> auth() {
            return Optional.empty();
        }

        @Override
        public Optional<String> caCert() {
            return caCert;
        }

        @Override
        public Optional<Boolean> insecureSkipTlsVerify() {
            return insecureSkipTlsVerify;
        }

        @Override
        public Optional<String> repo() {
            return Optional.empty();
        }

        @Override
        public Optional<String> namespace() {
            return Optional.empty();
        }

        @Override
        public Optional<String> workload() {
            return Optional.empty();
        }

        @Override
        public Optional<String> container() {
            return Optional.empty();
        }

        @Override
        public Optional<String> versionKey() {
            return Optional.empty();
        }

        @Override
        public Optional<Integer> pageSize() {
            return Optional.empty();
        }

        @Override
        public Optional<String> host() {
            return Optional.empty();
        }

        @Override
        public Optional<Integer> port() {
            return Optional.empty();
        }

        @Override
        public Optional<String> user() {
            return Optional.empty();
        }

        @Override
        public Optional<String> privateKey() {
            return Optional.empty();
        }

        @Override
        public Optional<String> privateKeyFile() {
            return Optional.empty();
        }

        @Override
        public Optional<String> hostKey() {
            return Optional.empty();
        }

        @Override
        public Optional<String> knownHosts() {
            return Optional.empty();
        }

        @Override
        public Optional<String> releaseField() {
            return Optional.empty();
        }

        @Override
        public Optional<Integer> maxTags() {
            return Optional.empty();
        }

        @Override
        public Optional<String> prereleaseFilter() {
            return Optional.empty();
        }

        @Override
        public Optional<String> registry() {
            return Optional.empty();
        }
    }
}
