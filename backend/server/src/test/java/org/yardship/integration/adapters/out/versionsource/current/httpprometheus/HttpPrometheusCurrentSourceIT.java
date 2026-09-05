package org.yardship.integration.adapters.out.versionsource.current.httpprometheus;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader.VersionSource.Auth;
import org.yardship.adapters.out.versionsource.current.CurrentVersionSourceFactory;
import org.yardship.adapters.out.versionsource.current.httpprometheus.HttpPrometheusCurrentSourceFactory;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import org.yardship.core.ports.out.CurrentVersionSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the real {@code http-prometheus} current-version kind, built end to end
 * through {@link HttpPrometheusCurrentSourceFactory#create} against a standalone WireMock server on
 * port 8089 — matching the style of {@code HttpHeaderCurrentSourceIT} and {@code HttpJsonCurrentSourceIT}.
 *
 * <p>{@code docs/adr/0033-http-prometheus-current-source.md} is the binding specification.
 * Unit-level extraction/message/parsing behavior lives in {@code HttpPrometheusCurrentSourceTests}
 * and {@code PrometheusExpositionTests}; this class exists to prove the real wire behavior a fake
 * body cannot: a genuine non-2xx status (which — UNLIKE {@code http-header} — this kind must
 * refuse), an actual redirect chain, and real transport-level auth.
 */
@QuarkusTest
class HttpPrometheusCurrentSourceIT {

    private static final VersionParser SEMVER_PARSER = new VersionParser(VersionScheme.SEMVER);
    private static final String METRIC = "blackbox_exporter_build_info";

    private static final String BLACKBOX_BODY = """
            # HELP blackbox_exporter_build_info A metric with a constant '1' value labeled by version, revision, branch.
            # TYPE blackbox_exporter_build_info gauge
            blackbox_exporter_build_info{branch="HEAD",goversion="go1.22.4",revision="0ec2a6b",version="0.25.0"} 1
            """;

    static WireMockServer wireMockServer;

    @Inject
    Instance<CurrentVersionSourceFactory> factories;

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(options().port(8089));
        wireMockServer.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMockServer.stop();
    }

    @BeforeEach
    void resetStubs() {
        wireMockServer.resetAll();
    }

    /**
     * Proves acceptance criterion 1: a blackbox-shaped body resolves end-to-end through a real
     * factory-built source.
     */
    @Test
    void version_resolvesABlackboxShapedBody_endToEndThroughTheFactory() {
        wireMockServer.stubFor(get(urlEqualTo("/metrics")).willReturn(
                aResponse().withStatus(200).withHeader("Content-Type", "text/plain").withBody(BLACKBOX_BODY)));

        CurrentVersionSource source = new HttpPrometheusCurrentSourceFactory().create(
                source("http://localhost:8089/metrics", METRIC), SEMVER_PARSER);

        assertEquals("0.25.0", source.version().value());
    }

    /**
     * <b>The regression guard distinguishing this kind from {@code http-header}.</b> Unlike
     * {@code http-header}'s deliberate status-blindness (ADR-0030), {@code http-prometheus} REQUIRES
     * a 2xx final response — the body IS the resource here, so a non-2xx response (a login page, a
     * proxy error page) must fail the read rather than be parsed as if it were metrics. Do not
     * "fix" this to match {@code http-header}'s status-blindness: doing so is precisely the
     * regression this test exists to catch.
     */
    @Test
    void version_throws_onANon2xxFinalResponse_namingTheStatusAndTheUrl() {
        wireMockServer.stubFor(get(urlEqualTo("/metrics")).willReturn(
                aResponse().withStatus(403).withBody("<html>Forbidden</html>")));

        CurrentVersionSource source = new HttpPrometheusCurrentSourceFactory().create(
                source("http://localhost:8089/metrics", METRIC), SEMVER_PARSER);

        RuntimeException ex = assertThrows(RuntimeException.class, source::version);
        assertTrue(ex.getMessage().contains("403"),
                "must name the observed status; was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("http://localhost:8089/metrics"),
                "must name the url; was: " + ex.getMessage());
    }

    @Test
    void version_followsARedirectChain_toAFinalMetricsBody() {
        wireMockServer.stubFor(get(urlEqualTo("/metrics")).willReturn(
                aResponse().withStatus(301).withHeader("Location", "/final-metrics")));
        wireMockServer.stubFor(get(urlEqualTo("/final-metrics")).willReturn(
                aResponse().withStatus(200).withBody(BLACKBOX_BODY)));

        CurrentVersionSource source = new HttpPrometheusCurrentSourceFactory().create(
                source("http://localhost:8089/metrics", METRIC), SEMVER_PARSER);

        assertEquals("0.25.0", source.version().value());
        wireMockServer.verify(getRequestedFor(urlEqualTo("/final-metrics")));
    }

    /**
     * Proves the kind is genuinely CDI-discovered (ADR-0005: no central dispatcher edited).
     */
    @Test
    void httpPrometheusCurrentSourceFactory_isDiscoveredAsACdiBean_exactlyOnce() {
        List<CurrentVersionSourceFactory> matching = StreamSupport.stream(factories.spliterator(), false)
                .filter(factory -> "http-prometheus".equals(factory.type()))
                .collect(Collectors.toList());

        assertEquals(1, matching.size(),
                "expected exactly one CDI-discovered CurrentVersionSourceFactory for "
                        + "'http-prometheus', found: " + matching);
    }

    @Test
    void version_withBasicAuth_sendsTheRenderedAuthorizationHeader_onTheWire() {
        wireMockServer.stubFor(get(urlEqualTo("/metrics")).willReturn(aResponse().withStatus(401)));
        wireMockServer.stubFor(get(urlEqualTo("/metrics"))
                .withBasicAuth("prom-bot", "s3cr3t")
                .willReturn(aResponse().withStatus(200).withBody(BLACKBOX_BODY)));

        CurrentVersionSource source = new HttpPrometheusCurrentSourceFactory().create(
                source("http://localhost:8089/metrics", METRIC, basicAuth("prom-bot", "s3cr3t")), SEMVER_PARSER);

        assertEquals("0.25.0", source.version().value());

        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("prom-bot:s3cr3t".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        wireMockServer.verify(getRequestedFor(urlEqualTo("/metrics"))
                .withHeader("Authorization", equalTo(expected)));
    }

    @Test
    void version_withBearerAuth_sendsTheRenderedAuthorizationHeader_onTheWire() {
        wireMockServer.stubFor(get(urlEqualTo("/metrics")).willReturn(aResponse().withStatus(401)));
        wireMockServer.stubFor(get(urlEqualTo("/metrics"))
                .withHeader("Authorization", equalTo("Bearer prom-token"))
                .willReturn(aResponse().withStatus(200).withBody(BLACKBOX_BODY)));

        CurrentVersionSource source = new HttpPrometheusCurrentSourceFactory().create(
                source("http://localhost:8089/metrics", METRIC, bearerAuth("prom-token")), SEMVER_PARSER);

        assertEquals("0.25.0", source.version().value());

        wireMockServer.verify(getRequestedFor(urlEqualTo("/metrics"))
                .withHeader("Authorization", equalTo("Bearer prom-token")));
    }

    @Test
    void version_withBearerFromFileAuth_reReadsTheTokenFile_onEveryCall(@TempDir Path dir) throws Exception {
        Path tokenFile = dir.resolve("token");
        Files.writeString(tokenFile, "file-tok\n");

        wireMockServer.stubFor(get(urlEqualTo("/metrics")).willReturn(aResponse().withStatus(401)));
        wireMockServer.stubFor(get(urlEqualTo("/metrics"))
                .withHeader("Authorization", equalTo("Bearer file-tok"))
                .willReturn(aResponse().withStatus(200).withBody(BLACKBOX_BODY)));

        CurrentVersionSource source = new HttpPrometheusCurrentSourceFactory().create(
                source("http://localhost:8089/metrics", METRIC, bearerFromFileAuth(tokenFile.toString())),
                SEMVER_PARSER);

        assertEquals("0.25.0", source.version().value());

        wireMockServer.verify(getRequestedFor(urlEqualTo("/metrics"))
                .withHeader("Authorization", equalTo("Bearer file-tok")));
    }

    /**
     * The decoy {@code version="9.9.9"} is the point: it proves {@code cfg.versionLabel()}
     * genuinely threads through the factory rather than being ignored in favour of a hard-coded
     * {@code "version"}.
     */
    @Test
    void version_honoursAConfiguredVersionLabel() {
        wireMockServer.stubFor(get(urlEqualTo("/metrics")).willReturn(aResponse().withStatus(200)
                .withBody("blackbox_exporter_build_info{version=\"9.9.9\",build_version=\"1.2.3\"} 1\n")));

        CurrentVersionSource source = new HttpPrometheusCurrentSourceFactory().create(
                sourceWithVersionLabel("http://localhost:8089/metrics", METRIC, Optional.of("build_version")),
                SEMVER_PARSER);

        assertEquals("1.2.3", source.version().value());
    }

    @Test
    void version_defaultsTheVersionLabelToVersion_whenAbsent() {
        wireMockServer.stubFor(get(urlEqualTo("/metrics")).willReturn(aResponse().withStatus(200)
                .withBody("blackbox_exporter_build_info{version=\"1.2.3\"} 1\n")));

        CurrentVersionSource source = new HttpPrometheusCurrentSourceFactory().create(
                sourceWithVersionLabel("http://localhost:8089/metrics", METRIC, Optional.empty()), SEMVER_PARSER);

        assertEquals("1.2.3", source.version().value());
    }

    private static ApplicationConfigLoader.VersionSource source(String url, String metric) {
        return new FakeVersionSource(Optional.of(url), Optional.of(metric), Optional.empty(), Optional.empty());
    }

    private static ApplicationConfigLoader.VersionSource source(String url, String metric, Auth auth) {
        return new FakeVersionSource(Optional.of(url), Optional.of(metric), Optional.empty(), Optional.of(auth));
    }

    private static ApplicationConfigLoader.VersionSource sourceWithVersionLabel(
            String url, String metric, Optional<String> versionLabel) {
        return new FakeVersionSource(Optional.of(url), Optional.of(metric), versionLabel, Optional.empty());
    }

    private static Auth basicAuth(String username, String password) {
        return new Auth() {
            @Override
            public Optional<String> type() {
                return Optional.of("basic");
            }

            @Override
            public Optional<String> username() {
                return Optional.of(username);
            }

            @Override
            public Optional<String> password() {
                return Optional.of(password);
            }

            @Override
            public Optional<String> token() {
                return Optional.empty();
            }

            @Override
            public Optional<String> tokenFile() {
                return Optional.empty();
            }
        };
    }

    private static Auth bearerAuth(String token) {
        return new Auth() {
            @Override
            public Optional<String> type() {
                return Optional.of("bearer");
            }

            @Override
            public Optional<String> username() {
                return Optional.empty();
            }

            @Override
            public Optional<String> password() {
                return Optional.empty();
            }

            @Override
            public Optional<String> token() {
                return Optional.of(token);
            }

            @Override
            public Optional<String> tokenFile() {
                return Optional.empty();
            }
        };
    }

    private static Auth bearerFromFileAuth(String tokenFilePath) {
        return new Auth() {
            @Override
            public Optional<String> type() {
                return Optional.of("bearer");
            }

            @Override
            public Optional<String> username() {
                return Optional.empty();
            }

            @Override
            public Optional<String> password() {
                return Optional.empty();
            }

            @Override
            public Optional<String> token() {
                return Optional.empty();
            }

            @Override
            public Optional<String> tokenFile() {
                return Optional.of(tokenFilePath);
            }
        };
    }

    /**
     * Fully implements {@link ApplicationConfigLoader.VersionSource}, defaulting every field not
     * exercised here to {@link Optional#empty()}. {@code type()} is fixed to
     * {@code "http-prometheus"}.
     */
    private static final class FakeVersionSource implements ApplicationConfigLoader.VersionSource {
        private final Optional<String> url;
        private final Optional<String> metric;
        private final Optional<String> versionLabel;
        private final Optional<Auth> auth;

        FakeVersionSource(Optional<String> url, Optional<String> metric, Optional<String> versionLabel,
                Optional<Auth> auth) {
            this.url = url;
            this.metric = metric;
            this.versionLabel = versionLabel;
            this.auth = auth;
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
            return versionLabel;
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
            return auth;
        }

        @Override
        public Optional<String> caCert() {
            return Optional.empty();
        }

        @Override
        public Optional<Boolean> insecureSkipTlsVerify() {
            return Optional.empty();
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
