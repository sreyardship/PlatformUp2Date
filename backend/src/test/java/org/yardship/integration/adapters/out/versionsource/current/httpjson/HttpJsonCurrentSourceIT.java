package org.yardship.integration.adapters.out.versionsource.current.httpjson;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader.VersionSource.Auth;
import org.yardship.adapters.out.versionsource.VersionFetchException;
import org.yardship.adapters.out.versionsource.current.CurrentVersionSourceFactory;
import org.yardship.adapters.out.versionsource.current.httpjson.HttpJsonCurrentSourceFactory;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import org.yardship.core.ports.out.CurrentVersionSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the real {@code http-json} current-version kind, built end to end through
 * {@link HttpJsonCurrentSourceFactory#create} against a standalone WireMock server on port 8089.
 *
 * <p>Intentionally thin: extraction, prerelease stripping and the 2xx gate are covered by
 * {@code HttpJsonVersionExtractorTests} with fake responses; redirects, TLS and auth rendering are
 * proven once for the shared transport in {@code HttpTransportIT}. This class proves the kind's
 * own rule on the wire — a real JSON body resolves, a real non-2xx fails — and one authenticated
 * fragment end to end, the full production path the dev {@code application.yml} Harbor entry
 * exercises.
 */
@QuarkusTest
class HttpJsonCurrentSourceIT {

    private static final VersionParser SEMVER_PARSER = new VersionParser(VersionScheme.SEMVER);

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

    @Test
    void version_parsesVersionJson_endToEndThroughTheFactory() {
        wireMockServer.stubFor(get(urlEqualTo("/current"))
                .willReturn(jsonResponse(200, "{\"version\":\"1.0.0\"}")));

        CurrentVersionSource source = new HttpJsonCurrentSourceFactory().create(
                source("http://localhost:8089/current", Optional.empty()), SEMVER_PARSER);

        assertEquals("1.0.0", source.version().value());
    }

    @Test
    void version_throwsAVersionFetchException_whenUpstreamReturnsNon2xx() {
        // A non-2xx is the http-json kind's own failure (unlike http-header's status-blindness),
        // mapped to a thrown exception so the service's per-app loop can count this app as failed.
        wireMockServer.stubFor(get(urlEqualTo("/current"))
                .willReturn(jsonResponse(403, "{\"message\":\"forbidden\"}")));

        CurrentVersionSource source = new HttpJsonCurrentSourceFactory().create(
                source("http://localhost:8089/current", Optional.empty()), SEMVER_PARSER);

        VersionFetchException ex = assertThrows(VersionFetchException.class, source::version);
        assertEquals(403, ex.status());
        assertTrue(ex.getMessage().contains("forbidden"), "must carry the body; was: " + ex.getMessage());
    }

    @Test
    void version_withValidHarborBasicAuthConfig_endToEnd_readsHarborVersion() {
        // Catch-all 401 registered FIRST — "last registered wins": the more specific withBasicAuth
        // stub (registered second) is applied to a matching authenticated request.
        wireMockServer.stubFor(get(urlEqualTo("/systeminfo"))
                .willReturn(aResponse().withStatus(401)));
        wireMockServer.stubFor(get(urlEqualTo("/systeminfo"))
                .withBasicAuth("harbor-bot", "s3cr3t")
                .willReturn(jsonResponse(200, "{\"harbor_version\":\"v2.13.0-6b7ecba1\"}")));

        CurrentVersionSource source = new HttpJsonCurrentSourceFactory().create(
                new FakeVersionSource(Optional.of("http://localhost:8089/systeminfo"),
                        Optional.of("/harbor_version"), Optional.of(true),
                        Optional.of(basicAuth("harbor-bot", "s3cr3t"))),
                SEMVER_PARSER);

        // The 'v' prefix is trimmed by the Version primitive; strip-prerelease drops the build hash.
        assertEquals("2.13.0", source.version().value());
    }

    /**
     * Proves {@code type: http-json} resolves through {@code VersionSourceResolver} with no central
     * dispatcher edited (ADR-0005) — the factory is genuinely discovered as a CDI bean, not merely
     * {@code new}-able.
     */
    @Test
    void httpJsonCurrentSourceFactory_isDiscoveredAsACdiBean_exactlyOnce() {
        List<CurrentVersionSourceFactory> matching = StreamSupport.stream(factories.spliterator(), false)
                .filter(factory -> "http-json".equals(factory.type()))
                .collect(Collectors.toList());

        assertEquals(1, matching.size(),
                "expected exactly one CDI-discovered CurrentVersionSourceFactory for 'http-json', found: "
                        + matching);
    }

    private static ApplicationConfigLoader.VersionSource source(String url, Optional<String> versionKey) {
        return new FakeVersionSource(Optional.of(url), versionKey, Optional.empty(), Optional.empty());
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

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder jsonResponse(int status, String body) {
        return aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body);
    }

    /**
     * Fully implements {@link ApplicationConfigLoader.VersionSource}, defaulting every field not
     * exercised here to {@link Optional#empty()}. {@code type()} is fixed to {@code "http-json"}.
     */
    private static final class FakeVersionSource implements ApplicationConfigLoader.VersionSource {
        private final Optional<String> url;
        private final Optional<String> versionKey;
        private final Optional<Boolean> stripPrerelease;
        private final Optional<Auth> auth;

        FakeVersionSource(Optional<String> url, Optional<String> versionKey, Optional<Boolean> stripPrerelease,
                Optional<Auth> auth) {
            this.url = url;
            this.versionKey = versionKey;
            this.stripPrerelease = stripPrerelease;
            this.auth = auth;
        }

        @Override
        public Optional<String> type() {
            return Optional.of("http-json");
        }

        @Override
        public Map<String, String> labels() {
            return Map.of();
        }

        @Override
        public Optional<String> url() {
            return url;
        }

        @Override
        public Optional<String> versionKey() {
            return versionKey;
        }

        @Override
        public Optional<Boolean> stripPrerelease() {
            return stripPrerelease;
        }

        @Override
        public Optional<Auth> auth() {
            return auth;
        }

        @Override
        public Optional<String> regex() {
            return Optional.empty();
        }

        @Override
        public Optional<String> versionHeader() {
            return Optional.empty();
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

        @Override
        public Optional<String> metric() {
            return Optional.empty();
        }

        @Override
        public Optional<String> versionLabel() {
            return Optional.empty();
        }
    }
}
