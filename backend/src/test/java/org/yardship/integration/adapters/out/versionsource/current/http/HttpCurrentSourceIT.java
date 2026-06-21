package org.yardship.integration.adapters.out.versionsource.current.http;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.adapters.out.versionsource.auth.BasicAuthFilter;
import org.yardship.adapters.out.versionsource.auth.BearerAuthFilter;
import org.yardship.adapters.out.versionsource.current.http.HttpCurrentVersionClientFactory;
import org.yardship.adapters.out.versionsource.current.FailedCurrentSource;
import org.yardship.adapters.out.versionsource.current.http.HttpCurrentSource;
import org.yardship.adapters.out.versionsource.current.http.HttpCurrentSourceFactory;
import org.yardship.core.domain.primitives.Version;
import org.yardship.core.ports.out.CurrentVersionSource;

import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the real {@link HttpCurrentSource} adapter, now a pure POJO wired to a REAL
 * {@link org.yardship.adapters.out.versionsource.current.http.HttpCurrentVersionClient} built by the injected
 * {@link HttpCurrentVersionClientFactory} against a standalone WireMock server on port 8089.
 *
 * <p>This is intentionally thin: per plan.md, the extraction/strip-prerelease/error-message behavior
 * that used to live here (because the old {@code HttpCurrentSource} built its own REST client) has
 * moved down to a true unit test ({@code HttpCurrentSourceTests}, fake client, no Arc). What remains
 * here is the thing only an IT can prove — that a client built by the real collaborator and handed to
 * the POJO source round-trips an actual HTTP call correctly, including the non-2xx mapping. The
 * "never sends an Authorization header" guardrail moved to {@code HttpCurrentVersionClientFactoryIT},
 * since that header decision is now entirely the client factory's concern.
 *
 * <p>{@code @QuarkusTest} is used so {@link HttpCurrentVersionClientFactory} can be injected — matching
 * the existing IT style.
 */
@QuarkusTest
class HttpCurrentSourceIT {

    static WireMockServer wireMockServer;

    @Inject
    HttpCurrentVersionClientFactory clientFactory;

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
    void read_parsesVersionJson_intoVersion() {
        wireMockServer.stubFor(get(urlEqualTo("/current"))
                .willReturn(jsonResponse(200, "{\"version\":\"1.0.0\"}")));

        HttpCurrentSource source = new HttpCurrentSource(
                clientFactory.build("http://localhost:8089/current", Optional.empty()), "/version", false);

        Version result = source.version();

        assertEquals("1.0.0", result.value());
    }

    @Test
    void read_throws_whenUpstreamReturnsNon2xx() {
        wireMockServer.stubFor(get(urlEqualTo("/current"))
                .willReturn(jsonResponse(403, "{\"message\":\"forbidden\"}")));

        HttpCurrentSource source = new HttpCurrentSource(
                clientFactory.build("http://localhost:8089/current", Optional.empty()), "/version", false);

        // A non-2xx is mapped to a thrown exception by the reused VersionResponseExceptionMapper,
        // so the service's per-app loop can count this app as failed.
        assertThrows(RuntimeException.class, source::version);
    }

    // --- Issue 02: Harbor Basic-auth round trip --------------------------------------------

    @Test
    void read_authenticatedWithBasicAuthFilter_returnsTheHarborVersion() {
        // Harbor 2.13+ only returns 'harbor_version' from /systeminfo to an authenticated caller —
        // an anonymous request gets a 2xx body WITHOUT the key (not modelled here; pinned by
        // HttpCurrentSourceTests at the unit level). Here a real BasicAuthFilter, built via the real
        // HttpCurrentVersionClientFactory, must round-trip a 401-guarded endpoint successfully.
        // WireMock resolves ties between equally-specific stubs by "last registered wins" — the
        // catch-all 401 must therefore be registered FIRST, so the more specific withBasicAuth
        // stub (registered second) is the one actually applied to a matching authenticated request.
        wireMockServer.stubFor(get(urlEqualTo("/systeminfo"))
                .willReturn(aResponse().withStatus(401)));
        wireMockServer.stubFor(get(urlEqualTo("/systeminfo"))
                .withBasicAuth("harbor-bot", "s3cr3t")
                .willReturn(jsonResponse(200, "{\"harbor_version\":\"v2.13.0\"}")));

        HttpCurrentSource source = new HttpCurrentSource(
                clientFactory.build("http://localhost:8089/systeminfo",
                        Optional.of(new BasicAuthFilter("harbor-bot", "s3cr3t"))),
                "/harbor_version",
                false);

        Version result = source.version();

        // The 'v' prefix is trimmed by the Version primitive (see Version.trimInput).
        assertEquals("2.13.0", result.value());
    }

    @Test
    void read_withWrongBasicAuthCredentials_throws_becauseUpstreamReturns401() {
        // Catch-all 401 registered FIRST — see the "last registered wins" note on
        // read_authenticatedWithBasicAuthFilter_returnsTheHarborVersion. Here it must be the one that
        // actually applies (the request below carries WRONG credentials, so it should never match
        // the withBasicAuth stub regardless of registration order — registering it last for
        // consistency with the other tests in this class, and to prove the 401 stub is reachable).
        wireMockServer.stubFor(get(urlEqualTo("/systeminfo"))
                .willReturn(aResponse().withStatus(401)));
        wireMockServer.stubFor(get(urlEqualTo("/systeminfo"))
                .withBasicAuth("harbor-bot", "s3cr3t")
                .willReturn(jsonResponse(200, "{\"harbor_version\":\"v2.13.0\"}")));

        HttpCurrentSource source = new HttpCurrentSource(
                clientFactory.build("http://localhost:8089/systeminfo",
                        Optional.of(new BasicAuthFilter("harbor-bot", "WRONG"))),
                "/harbor_version",
                false);

        assertThrows(RuntimeException.class, source::version);
    }

    @Test
    void factoryCreate_withValidHarborBasicAuthConfig_endToEnd_readsHarborVersion() {
        // Drives the FULL production path the dev application.yml entry exercises:
        // HttpCurrentSourceFactory.create(cfg) -> real HttpCurrentVersionClientFactory -> BasicAuthFilter
        // -> real HTTP call through WireMock. Catch-all 401 registered FIRST — see the
        // "last registered wins" note on read_authenticatedWithBasicAuthFilter_returnsTheHarborVersion.
        wireMockServer.stubFor(get(urlEqualTo("/systeminfo"))
                .willReturn(aResponse().withStatus(401)));
        wireMockServer.stubFor(get(urlEqualTo("/systeminfo"))
                .withBasicAuth("harbor-bot", "s3cr3t")
                .willReturn(jsonResponse(200, "{\"harbor_version\":\"v2.13.0\"}")));

        HttpCurrentSourceFactory httpFactory = new HttpCurrentSourceFactory(clientFactory);
        CurrentVersionSource result = httpFactory.create(harborConfig(
                Optional.of("harbor-bot"), Optional.of("s3cr3t")));

        assertInstanceOf(HttpCurrentSource.class, result);
        // The 'v' prefix is trimmed by the Version primitive (see Version.trimInput).
        assertEquals("2.13.0", result.version().value());
    }

    @Test
    void factoryCreate_withBlankHarborPassword_yieldsAFailedCurrentSource_notAVersionKeyError() {
        // The clear-failure guarantee: a blank credential (e.g. an unset HARBOR_PASS env var
        // resolving to "" via SmallRye expansion) must surface as a FailedCurrentSource with an
        // auth-specific message — NEVER the misleading "version-key did not resolve" error that an
        // unauthenticated 2xx-without-harbor_version response would otherwise produce.
        HttpCurrentSourceFactory httpFactory = new HttpCurrentSourceFactory(clientFactory);
        CurrentVersionSource result = httpFactory.create(harborConfig(
                Optional.of("harbor-bot"), Optional.of("")));

        assertInstanceOf(FailedCurrentSource.class, result);
        IllegalStateException ex = assertThrows(IllegalStateException.class, result::version);
        assertFalse(ex.getMessage().toLowerCase().contains("version-key"),
                "must not be the version-key-did-not-resolve message; was: " + ex.getMessage());
    }

    // --- Issue 03: Bearer-auth round trip ---------------------------------------------------

    @Test
    void read_authenticatedWithBearerAuthFilter_returnsTheVersion() {
        // Catch-all 401 registered FIRST, specific bearer-matching 200 stub registered LAST — see
        // the "last registered wins" note on read_authenticatedWithBasicAuthFilter_returnsTheHarborVersion.
        wireMockServer.stubFor(get(urlEqualTo("/current"))
                .willReturn(aResponse().withStatus(401)));
        wireMockServer.stubFor(get(urlEqualTo("/current"))
                .withHeader("Authorization", equalTo("Bearer gh-token"))
                .willReturn(jsonResponse(200, "{\"version\":\"v3.1.0\"}")));

        HttpCurrentSource source = new HttpCurrentSource(
                clientFactory.build("http://localhost:8089/current",
                        Optional.of(new BearerAuthFilter("gh-token"))),
                "/version",
                false);

        Version result = source.version();

        // The 'v' prefix is trimmed by the Version primitive (see Version.trimInput).
        assertEquals("3.1.0", result.value());
    }

    @Test
    void factoryCreate_withValidBearerAuthConfig_endToEnd_readsTheVersion() {
        // Drives the FULL production path: HttpCurrentSourceFactory.create(cfg) -> real
        // HttpCurrentVersionClientFactory -> BearerAuthFilter -> real HTTP call through WireMock.
        // Catch-all 401 registered FIRST, specific bearer-matching 200 stub registered LAST.
        wireMockServer.stubFor(get(urlEqualTo("/current"))
                .willReturn(aResponse().withStatus(401)));
        wireMockServer.stubFor(get(urlEqualTo("/current"))
                .withHeader("Authorization", equalTo("Bearer gh-token"))
                .willReturn(jsonResponse(200, "{\"version\":\"v3.1.0\"}")));

        HttpCurrentSourceFactory httpFactory = new HttpCurrentSourceFactory(clientFactory);
        CurrentVersionSource result = httpFactory.create(bearerConfig(Optional.of("gh-token")));

        assertInstanceOf(HttpCurrentSource.class, result);
        assertEquals("3.1.0", result.version().value());
    }

    @Test
    void factoryCreate_withBlankBearerToken_yieldsAFailedCurrentSource_notAVersionKeyError() {
        // The clear-failure guarantee: a blank token (e.g. an unset GH_TOKEN env var resolving to
        // "" via SmallRye expansion) must surface as a FailedCurrentSource with an auth-specific
        // message — NEVER the misleading "version-key did not resolve" error.
        HttpCurrentSourceFactory httpFactory = new HttpCurrentSourceFactory(clientFactory);
        CurrentVersionSource result = httpFactory.create(bearerConfig(Optional.of("")));

        assertInstanceOf(FailedCurrentSource.class, result);
        IllegalStateException ex = assertThrows(IllegalStateException.class, result::version);
        assertFalse(ex.getMessage().toLowerCase().contains("version-key"),
                "must not be the version-key-did-not-resolve message; was: " + ex.getMessage());
    }

    private static ApplicationConfigLoader.VersionSource bearerConfig(Optional<String> token) {
        return new ApplicationConfigLoader.VersionSource() {
            @Override
            public String type() {
                return "http";
            }

            @Override
            public Optional<String> url() {
                return Optional.of("http://localhost:8089/current");
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
                return Optional.of("/version");
            }

            @Override
            public Optional<Boolean> stripPrerelease() {
                return Optional.of(false);
            }

            @Override
            public Optional<ApplicationConfigLoader.VersionSource.Auth> auth() {
                return Optional.of(new ApplicationConfigLoader.VersionSource.Auth() {
                    @Override
                    public String type() {
                        return "bearer";
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
                        return token;
                    }
                });
            }

            @Override
            public Optional<Integer> pageSize() {
                return Optional.empty();
            }
        };
    }

    private static ApplicationConfigLoader.VersionSource harborConfig(
            Optional<String> username, Optional<String> password) {
        return new ApplicationConfigLoader.VersionSource() {
            @Override
            public String type() {
                return "http";
            }

            @Override
            public Optional<String> url() {
                return Optional.of("http://localhost:8089/systeminfo");
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
                return Optional.of("/harbor_version");
            }

            @Override
            public Optional<Boolean> stripPrerelease() {
                return Optional.of(true);
            }

            @Override
            public Optional<ApplicationConfigLoader.VersionSource.Auth> auth() {
                return Optional.of(new ApplicationConfigLoader.VersionSource.Auth() {
                    @Override
                    public String type() {
                        return "basic";
                    }

                    @Override
                    public Optional<String> username() {
                        return username;
                    }

                    @Override
                    public Optional<String> password() {
                        return password;
                    }

                    @Override
                    public Optional<String> token() {
                        return Optional.empty();
                    }
                });
            }

            @Override
            public Optional<Integer> pageSize() {
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
}
