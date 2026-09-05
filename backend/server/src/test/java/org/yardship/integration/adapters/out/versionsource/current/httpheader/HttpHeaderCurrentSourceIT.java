package org.yardship.integration.adapters.out.versionsource.current.httpheader;

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
import org.yardship.adapters.out.versionsource.current.CurrentVersionSourceFactory;
import org.yardship.adapters.out.versionsource.current.httpheader.HttpHeaderCurrentSourceFactory;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import org.yardship.core.ports.out.CurrentVersionSource;

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
 * Integration test for the real {@code http-header} current-version kind, built end to end through
 * {@link HttpHeaderCurrentSourceFactory#create} against a standalone WireMock server on port 8089 —
 * matching the style of {@code HttpJsonCurrentSourceIT}.
 *
 * <p>{@code docs/adr/0030-http-header-current-source.md} is the binding specification. Unit-level
 * extraction/message behavior lives in {@code HttpHeaderCurrentSourceTests}; this class exists to
 * prove the real wire behavior a fake response cannot: an actual non-2xx status, an actual redirect
 * chain, and header case as an actual server sends it.
 */
@QuarkusTest
class HttpHeaderCurrentSourceIT {

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

    /**
     * <b>THE regression guard for ADR-0030's central, most-likely-to-be-"corrected" decision.</b>
     *
     * <p>The {@code http-header} current source reads its configured header off the FINAL response
     * regardless of that response's status code; the status is used only to compose a failure
     * message, never to gate the read. This exists because a secured Jenkins refuses its anonymous
     * top page with 403 and volunteers its version anyway:
     * <pre>
     * $ curl -sI https://ci.jenkins.io/
     * HTTP/2 403
     * x-jenkins: 2.568.2
     * </pre>
     * Every real Jenkins is a secured Jenkins — see
     * {@code docs/adr/0030-http-header-current-source.md}, "The status code is ignored; only the
     * header's presence matters". <b>Do not "fix" this to require a 2xx status</b>: doing so is
     * precisely the regression this test exists to catch, and it would fail every real Jenkins this
     * kind was built for.
     */
    @Test
    void version_readsTheHeader_fromA403Response_ignoringTheStatusCode() {
        wireMockServer.stubFor(get(urlEqualTo("/")).willReturn(aResponse()
                .withStatus(403)
                .withHeader("x-jenkins", "2.568.2")
                .withBody("<html>Forbidden</html>")));

        CurrentVersionSource source = new HttpHeaderCurrentSourceFactory().create(
                source("http://localhost:8089/", "X-Jenkins"), SEMVER_PARSER);

        assertEquals("2.568.2", source.version().value());
    }

    @Test
    void version_readsTheHeader_fromA200Response_identicallyToA403() {
        wireMockServer.stubFor(get(urlEqualTo("/")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("x-jenkins", "2.568.2")
                .withBody("<html>OK</html>")));

        CurrentVersionSource source = new HttpHeaderCurrentSourceFactory().create(
                source("http://localhost:8089/", "X-Jenkins"), SEMVER_PARSER);

        assertEquals("2.568.2", source.version().value());
    }

    @Test
    void version_followsARedirectChain_andReadsTheHeaderOffTheFinalResponse() {
        // The intermediate hop deliberately carries no x-jenkins header: if this source wrongly read
        // headers off the FIRST response instead of following the redirect, this would fail with an
        // absent-header error rather than silently returning the wrong thing.
        wireMockServer.stubFor(get(urlEqualTo("/")).willReturn(aResponse()
                .withStatus(301)
                .withHeader("Location", "/final")));
        wireMockServer.stubFor(get(urlEqualTo("/final")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("x-jenkins", "2.568.2")));

        CurrentVersionSource source = new HttpHeaderCurrentSourceFactory().create(
                source("http://localhost:8089/", "X-Jenkins"), SEMVER_PARSER);

        assertEquals("2.568.2", source.version().value());
        wireMockServer.verify(getRequestedFor(urlEqualTo("/final")));
    }

    @Test
    void version_throws_whenTheResponseCarriesNoSuchHeader_namingTheObservedStatus() {
        wireMockServer.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(200)));

        CurrentVersionSource source = new HttpHeaderCurrentSourceFactory().create(
                source("http://localhost:8089/", "X-Jenkins"), SEMVER_PARSER);

        RuntimeException ex = assertThrows(RuntimeException.class, source::version);
        assertTrue(ex.getMessage().contains("X-Jenkins"),
                "must name the configured header; was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("200"),
                "must name the observed status; was: " + ex.getMessage());
    }

    /**
     * Proves acceptance criterion 1: {@code type: http-header} resolves through
     * {@link org.yardship.adapters.out.versionsource.VersionSourceResolver} with no central
     * dispatcher edited (ADR-0005) — i.e. {@link HttpHeaderCurrentSourceFactory} is genuinely
     * discovered as a CDI bean rather than merely {@code new}-able. If {@code @ApplicationScoped}
     * were removed from the factory, {@code factories} would not contain it and this test would
     * fail while every other test in this class (which builds the factory with {@code new}) stayed
     * green.
     */
    @Test
    void httpHeaderCurrentSourceFactory_isDiscoveredAsACdiBean_exactlyOnce() {
        List<CurrentVersionSourceFactory> matching = StreamSupport.stream(factories.spliterator(), false)
                .filter(factory -> "http-header".equals(factory.type()))
                .collect(Collectors.toList());

        assertEquals(1, matching.size(),
                "expected exactly one CDI-discovered CurrentVersionSourceFactory for 'http-header', found: "
                        + matching);
    }

    @Test
    void version_withBasicAuth_sendsTheRenderedAuthorizationHeader_onTheWire() {
        wireMockServer.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(401)));
        wireMockServer.stubFor(get(urlEqualTo("/"))
                .withBasicAuth("jenkins-bot", "s3cr3t")
                .willReturn(aResponse().withStatus(200).withHeader("x-jenkins", "2.568.2")));

        CurrentVersionSource source = new HttpHeaderCurrentSourceFactory().create(
                source("http://localhost:8089/", "X-Jenkins", basicAuth("jenkins-bot", "s3cr3t")), SEMVER_PARSER);

        assertEquals("2.568.2", source.version().value());

        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("jenkins-bot:s3cr3t".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        wireMockServer.verify(getRequestedFor(urlEqualTo("/"))
                .withHeader("Authorization", equalTo(expected)));
    }

    @Test
    void version_withBearerAuth_sendsTheRenderedAuthorizationHeader_onTheWire() {
        wireMockServer.stubFor(get(urlEqualTo("/")).willReturn(aResponse().withStatus(401)));
        wireMockServer.stubFor(get(urlEqualTo("/"))
                .withHeader("Authorization", equalTo("Bearer gh-token"))
                .willReturn(aResponse().withStatus(200).withHeader("x-jenkins", "2.568.2")));

        CurrentVersionSource source = new HttpHeaderCurrentSourceFactory().create(
                source("http://localhost:8089/", "X-Jenkins", bearerAuth("gh-token")), SEMVER_PARSER);

        assertEquals("2.568.2", source.version().value());

        wireMockServer.verify(getRequestedFor(urlEqualTo("/"))
                .withHeader("Authorization", equalTo("Bearer gh-token")));
    }

    private static ApplicationConfigLoader.VersionSource source(String url, String header) {
        return new FakeVersionSource(Optional.of(url), Optional.of(header), Optional.empty());
    }

    private static ApplicationConfigLoader.VersionSource source(String url, String header, Auth auth) {
        return new FakeVersionSource(Optional.of(url), Optional.of(header), Optional.of(auth));
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

    /**
     * Fully implements {@link ApplicationConfigLoader.VersionSource}, defaulting every field not
     * exercised here to {@link Optional#empty()}. {@code type()} is fixed to {@code "http-header"}.
     */
    private static final class FakeVersionSource implements ApplicationConfigLoader.VersionSource {
        private final Optional<String> url;
        private final Optional<String> versionHeader;
        private final Optional<Auth> auth;

        FakeVersionSource(Optional<String> url, Optional<String> versionHeader, Optional<Auth> auth) {
            this.url = url;
            this.versionHeader = versionHeader;
            this.auth = auth;
        }

        @Override
        public Optional<String> type() {
            return Optional.of("http-header");
        }

        @Override
        public Optional<String> url() {
            return url;
        }

        @Override
        public Optional<String> versionHeader() {
            return versionHeader;
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
