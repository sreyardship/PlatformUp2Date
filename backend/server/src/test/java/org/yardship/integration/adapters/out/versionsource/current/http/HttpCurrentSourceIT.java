package org.yardship.integration.adapters.out.versionsource.current.http;

import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientRequestFilter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.adapters.out.versionsource.VersionFetchException;
import org.yardship.adapters.out.versionsource.auth.BasicAuthFilter;
import org.yardship.adapters.out.versionsource.auth.BearerAuthFilter;
import org.yardship.adapters.out.versionsource.auth.FileBearerAuthFilter;
import org.yardship.adapters.out.versionsource.current.http.HttpCurrentVersionClientFactory;
import org.yardship.adapters.out.versionsource.current.http.HttpCurrentSource;
import org.yardship.adapters.out.versionsource.current.http.HttpCurrentSourceFactory;
import org.yardship.core.domain.primitives.VersionValue;
import org.yardship.core.ports.out.CurrentVersionSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
 * <p>Factory config-validation cases (blank password, blank token → FailedCurrentSource) are owned
 * exhaustively by {@code HttpCurrentSourceFactoryTests} at the unit level and are not re-asserted here.
 * Auth-on-the-wire is owned by the unit filter tests + {@code HttpCurrentVersionClientFactoryIT};
 * the authenticated source path is proven by the factory end-to-end happy paths below.
 *
 * <p>{@code @QuarkusTest} is used so {@link HttpCurrentVersionClientFactory} can be injected — matching
 * the existing IT style.
 */
@QuarkusTest
class HttpCurrentSourceIT {
    private static final VersionParser SEMVER_PARSER = new VersionParser(VersionScheme.SEMVER);

    static WireMockServer wireMockServer;
    static WireMockServer crossOriginWireMockServer;
    static WireMockServer httpsWireMockServer;

    @Inject
    HttpCurrentVersionClientFactory clientFactory;

    @BeforeAll
    static void startWireMock() throws Exception {
        wireMockServer = new WireMockServer(options().port(8089));
        wireMockServer.start();
        crossOriginWireMockServer = new WireMockServer(options().port(8091));
        crossOriginWireMockServer.start();
        httpsWireMockServer = new WireMockServer(options()
                .httpDisabled(true)
                .dynamicHttpsPort()
                .keystorePath(resourcePath("tls/wiremock-localhost.p12"))
                .keystorePassword("password")
                .keyManagerPassword("password")
                .keystoreType("PKCS12"));
        httpsWireMockServer.start();
    }

    @AfterAll
    static void stopWireMock() {
        httpsWireMockServer.stop();
        crossOriginWireMockServer.stop();
        wireMockServer.stop();
    }

    @BeforeEach
    void resetStubs() {
        wireMockServer.resetAll();
        crossOriginWireMockServer.resetAll();
        httpsWireMockServer.resetAll();
    }

    @Test
    void read_parsesVersionJson_intoVersion() {
        wireMockServer.stubFor(get(urlEqualTo("/current"))
                .willReturn(jsonResponse(200, "{\"version\":\"1.0.0\"}")));

        HttpCurrentSource source = new HttpCurrentSource(
                clientFactory.build("http://localhost:8089/current", Optional.empty(), Optional.empty(), false), "/version", false, SEMVER_PARSER);

        VersionValue result = source.version();

        assertEquals("1.0.0", result.value());
    }

    @Test
    void read_follows301Redirect_toFinalJsonAndResolvesVersion() {
        wireMockServer.stubFor(get(urlEqualTo("/current"))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"version\":\"1.0.0\"}")
                        .withHeader("Location", "/canonical/current")));
        wireMockServer.stubFor(get(urlEqualTo("/canonical/current"))
                .willReturn(jsonResponse(200, "{\"version\":\"2.4.0\"}")));

        HttpCurrentSource source = new HttpCurrentSource(
                clientFactory.build("http://localhost:8089/current", Optional.empty(), Optional.empty(), false),
                "/version", false, SEMVER_PARSER);

        VersionValue result = source.version();

        assertEquals("2.4.0", result.value());
        wireMockServer.verify(getRequestedFor(urlEqualTo("/canonical/current")));
    }

    @ParameterizedTest(name = "HTTP {0} redirect")
    @ValueSource(ints = {301, 302, 303, 307, 308})
    void read_followsEachSupportedRedirectStatus_toTheFinalJsonEndpoint(int status) {
        String initialEndpoint = "/redirect-status-" + status;
        String finalEndpoint = initialEndpoint + "-final";
        wireMockServer.stubFor(get(urlEqualTo(initialEndpoint))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withBody("intermediate response is not the final JSON")
                        .withHeader("Location", finalEndpoint)));
        wireMockServer.stubFor(get(urlEqualTo(finalEndpoint))
                .willReturn(jsonResponse(200, "{\"version\":\"2.8.0\"}")));

        HttpCurrentSource source = new HttpCurrentSource(
                clientFactory.build("http://localhost:8089" + initialEndpoint,
                        Optional.empty(), Optional.empty(), false),
                "/version", false, SEMVER_PARSER);

        assertEquals("2.8.0", source.version().value());
        wireMockServer.verify(getRequestedFor(urlEqualTo(initialEndpoint)));
        wireMockServer.verify(getRequestedFor(urlEqualTo(finalEndpoint)));
    }

    @Test
    void read_redirectLoop_terminatesAsSourceReadFailureAfterBoundedHops() {
        wireMockServer.stubFor(get(urlEqualTo("/redirect-loop"))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", "/redirect-loop")));

        HttpCurrentSource source = new HttpCurrentSource(
                clientFactory.build("http://localhost:8089/redirect-loop",
                        Optional.empty(), Optional.empty(), false),
                "/version", false, SEMVER_PARSER);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, source::version);
        assertEquals("Too many redirects while reading current version at http://localhost:8089/redirect-loop",
                thrown.getMessage());
        wireMockServer.verify(11, getRequestedFor(urlEqualTo("/redirect-loop")));
    }

    @Test
    void read_repeatedCalls_retraversePermanentRedirect_withoutCachingTarget() {
        wireMockServer.stubFor(get(urlEqualTo("/permanent-current"))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", "/permanent-canonical")));
        wireMockServer.stubFor(get(urlEqualTo("/permanent-canonical"))
                .willReturn(jsonResponse(200, "{\"version\":\"2.9.0\"}")));

        HttpCurrentSource source = new HttpCurrentSource(
                clientFactory.build("http://localhost:8089/permanent-current",
                        Optional.empty(), Optional.empty(), false),
                "/version", false, SEMVER_PARSER);

        assertEquals("2.9.0", source.version().value());
        assertEquals("2.9.0", source.version().value());
        wireMockServer.verify(2, getRequestedFor(urlEqualTo("/permanent-current")));
        wireMockServer.verify(2, getRequestedFor(urlEqualTo("/permanent-canonical")));
    }

    @Test
    void read_redirectLocationPreservesQueryParameters_onFinalJsonRequest() {
        wireMockServer.stubFor(get(urlEqualTo("/current-with-query"))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", "/canonical/current?channel=stable&region=eu")));
        wireMockServer.stubFor(get(urlEqualTo("/canonical/current?channel=stable&region=eu"))
                .willReturn(jsonResponse(200, "{\"version\":\"2.9.0\"}")));

        HttpCurrentSource source = new HttpCurrentSource(
                clientFactory.build("http://localhost:8089/current-with-query",
                        Optional.empty(), Optional.empty(), false),
                "/version", false, SEMVER_PARSER);

        assertEquals("2.9.0", source.version().value());
        wireMockServer.verify(getRequestedFor(urlEqualTo("/canonical/current?channel=stable&region=eu")));
    }

    @Test
    void read_withBasicAuth_followsSameOriginRedirect_withAuthorizationHeaderIntact() {
        wireMockServer.stubFor(get(urlEqualTo("/authenticated-current"))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", "/authenticated-canonical")));
        wireMockServer.stubFor(get(urlEqualTo("/authenticated-canonical"))
                .withHeader("Authorization", equalTo("Basic "
                        + java.util.Base64.getEncoder().encodeToString("scraper:s3cr3t".getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                .willReturn(jsonResponse(200, "{\"version\":\"2.5.0\"}")));

        HttpCurrentSource source = new HttpCurrentSource(
                clientFactory.build(
                        "http://localhost:8089/authenticated-current",
                        Optional.of(new BasicAuthFilter("scraper", "s3cr3t")),
                        Optional.empty(), false),
                "/version", false, SEMVER_PARSER);

        VersionValue result = source.version();

        assertEquals("2.5.0", result.value());
        wireMockServer.verify(getRequestedFor(urlEqualTo("/authenticated-canonical"))
                .withHeader("Authorization", equalTo("Basic "
                        + java.util.Base64.getEncoder().encodeToString("scraper:s3cr3t".getBytes(java.nio.charset.StandardCharsets.UTF_8)))));
    }

    @ParameterizedTest(name = "{0} auth")
    @ValueSource(strings = {"basic", "static-bearer", "file-bearer"})
    void read_withEachSupportedAuthFilter_retainsAuthorization_onSameOriginRedirect(
            String authType, @TempDir Path tempDir) throws IOException {
        String endpoint = "/same-origin-" + authType;
        String redirectedEndpoint = "/same-origin-" + authType + "-canonical";
        String authorization;
        ClientRequestFilter authFilter;
        switch (authType) {
            case "basic" -> {
                authorization = "Basic " + java.util.Base64.getEncoder().encodeToString(
                        "scraper:s3cr3t".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                authFilter = new BasicAuthFilter("scraper", "s3cr3t");
            }
            case "static-bearer" -> {
                authorization = "Bearer static-token";
                authFilter = new BearerAuthFilter("static-token");
            }
            case "file-bearer" -> {
                Path tokenFile = tempDir.resolve("token");
                Files.writeString(tokenFile, "  file-token\n");
                authorization = "Bearer file-token";
                authFilter = new FileBearerAuthFilter(tokenFile.toString());
            }
            default -> throw new IllegalArgumentException("Unknown auth type: " + authType);
        }

        wireMockServer.stubFor(get(urlEqualTo(endpoint))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", redirectedEndpoint)));
        wireMockServer.stubFor(get(urlEqualTo(redirectedEndpoint))
                .withHeader("Authorization", equalTo(authorization))
                .willReturn(jsonResponse(200, "{\"version\":\"2.7.0\"}")));

        HttpCurrentSource source = new HttpCurrentSource(
                clientFactory.build("http://localhost:8089" + endpoint,
                        Optional.of(authFilter), Optional.empty(), false),
                "/version", false, SEMVER_PARSER);

        assertEquals("2.7.0", source.version().value());
        wireMockServer.verify(getRequestedFor(urlEqualTo(redirectedEndpoint))
                .withHeader("Authorization", equalTo(authorization)));
    }

    @Test
    void read_withBasicAuth_doesNotForwardAuthorization_toCrossOriginSuccessfulTarget() {
        String authorization = "Basic " + java.util.Base64.getEncoder().encodeToString(
                "scraper:s3cr3t".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        wireMockServer.stubFor(get(urlEqualTo("/cross-origin-current"))
                .withHeader("Authorization", equalTo(authorization))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", "http://127.0.0.1:8091/public-current")));
        crossOriginWireMockServer.stubFor(get(urlEqualTo("/public-current"))
                .withHeader("Authorization", absent())
                .willReturn(jsonResponse(200, "{\"version\":\"2.6.0\"}")));

        HttpCurrentSource source = new HttpCurrentSource(
                clientFactory.build(
                        "http://localhost:8089/cross-origin-current",
                        Optional.of(new BasicAuthFilter("scraper", "s3cr3t")),
                        Optional.empty(), false),
                "/version", false, SEMVER_PARSER);

        assertEquals("2.6.0", source.version().value());
        crossOriginWireMockServer.verify(getRequestedFor(urlEqualTo("/public-current"))
                .withHeader("Authorization", absent()));
    }

    @Test
    void read_withBasicAuth_doesNotForwardAuthorization_onHttpToHttpsSameHostRedirect() {
        String authorization = "Basic " + java.util.Base64.getEncoder().encodeToString(
                "scraper:s3cr3t".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        wireMockServer.stubFor(get(urlEqualTo("/scheme-change-current"))
                .withHeader("Authorization", equalTo(authorization))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", "https://localhost:" + httpsWireMockServer.httpsPort()
                                + "/scheme-change-canonical")));
        httpsWireMockServer.stubFor(get(urlEqualTo("/scheme-change-canonical"))
                .withHeader("Authorization", absent())
                .willReturn(jsonResponse(200, "{\"version\":\"2.6.2\"}")));

        HttpCurrentSource source = new HttpCurrentSource(
                clientFactory.build(
                        "http://localhost:8089/scheme-change-current",
                        Optional.of(new BasicAuthFilter("scraper", "s3cr3t")),
                        Optional.empty(), true),
                "/version", false, SEMVER_PARSER);

        assertEquals("2.6.2", source.version().value());
        httpsWireMockServer.verify(getRequestedFor(urlEqualTo("/scheme-change-canonical"))
                .withHeader("Authorization", absent()));
    }

    @Test
    void read_withBasicAuth_doesNotForwardAuthorization_whenOnlyEffectivePortChanges() {
        String authorization = "Basic " + java.util.Base64.getEncoder().encodeToString(
                "scraper:s3cr3t".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        wireMockServer.stubFor(get(urlEqualTo("/port-change-current"))
                .withHeader("Authorization", equalTo(authorization))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", "http://localhost:8091/port-change-canonical")));
        crossOriginWireMockServer.stubFor(get(urlEqualTo("/port-change-canonical"))
                .withHeader("Authorization", absent())
                .willReturn(jsonResponse(200, "{\"version\":\"2.6.1\"}")));

        HttpCurrentSource source = new HttpCurrentSource(
                clientFactory.build(
                        "http://localhost:8089/port-change-current",
                        Optional.of(new BasicAuthFilter("scraper", "s3cr3t")),
                        Optional.empty(), false),
                "/version", false, SEMVER_PARSER);

        assertEquals("2.6.1", source.version().value());
        crossOriginWireMockServer.verify(getRequestedFor(urlEqualTo("/port-change-canonical"))
                .withHeader("Authorization", absent()));
    }

    @Test
    void read_afterRedirectToFinalNon2xx_throwsMappedVersionFetchException() {
        wireMockServer.stubFor(get(urlEqualTo("/current"))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", "/canonical/current")));
        String body = "{\"message\":\"forbidden\"}";
        wireMockServer.stubFor(get(urlEqualTo("/canonical/current"))
                .willReturn(jsonResponse(403, body)));

        HttpCurrentSource source = new HttpCurrentSource(
                clientFactory.build("http://localhost:8089/current", Optional.empty(), Optional.empty(), false),
                "/version", false, SEMVER_PARSER);

        // The redirect is followed, but the final non-2xx still goes through the mapper and escapes
        // this source read so ApplicationVersionService can isolate the failed current side.
        VersionFetchException thrown = assertThrows(VersionFetchException.class, source::version);
        assertEquals(403, thrown.status());
        assertEquals(body, thrown.body());
        wireMockServer.verify(getRequestedFor(urlEqualTo("/canonical/current")));
    }

    // --- Issue 02: Harbor Basic-auth end-to-end (factory path) --------------------------------

    @Test
    void factoryCreate_withValidHarborBasicAuthConfig_endToEnd_readsHarborVersion() {
        // Drives the FULL production path the dev application.yml entry exercises:
        // HttpCurrentSourceFactory.create(cfg) -> real HttpCurrentVersionClientFactory -> BasicAuthFilter
        // -> real HTTP call through WireMock. Catch-all 401 registered FIRST — see the
        // "last registered wins" note on WireMock stub ordering: the more specific withBasicAuth
        // stub (registered second) is applied to a matching authenticated request.
        wireMockServer.stubFor(get(urlEqualTo("/systeminfo"))
                .willReturn(aResponse().withStatus(401)));
        wireMockServer.stubFor(get(urlEqualTo("/systeminfo"))
                .withBasicAuth("harbor-bot", "s3cr3t")
                .willReturn(jsonResponse(200, "{\"harbor_version\":\"v2.13.0\"}")));

        HttpCurrentSourceFactory httpFactory = new HttpCurrentSourceFactory(clientFactory);
        CurrentVersionSource result = httpFactory.create(harborConfig(
                Optional.of("harbor-bot"), Optional.of("s3cr3t")), SEMVER_PARSER);

        assertInstanceOf(HttpCurrentSource.class, result);
        // The 'v' prefix is trimmed by the Version primitive (see Version.trimInput).
        assertEquals("2.13.0", result.version().value());
    }

    // --- Issue 03: Bearer-auth end-to-end (factory path) --------------------------------------

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
        CurrentVersionSource result = httpFactory.create(bearerConfig(Optional.of("gh-token")), SEMVER_PARSER);

        assertInstanceOf(HttpCurrentSource.class, result);
        assertEquals("3.1.0", result.version().value());
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
            public Optional<String> regex() {
                return Optional.empty();
            }

            @Override
            public Optional<String> host() { return Optional.empty(); }

            @Override
            public Optional<Integer> port() { return Optional.empty(); }

            @Override
            public Optional<String> user() { return Optional.empty(); }

            @Override
            public Optional<String> privateKey() { return Optional.empty(); }

            @Override
            public Optional<String> privateKeyFile() { return Optional.empty(); }

            @Override
            public Optional<String> hostKey() { return Optional.empty(); }

            @Override
            public Optional<String> knownHosts() { return Optional.empty(); }

            @Override
            public Optional<String> releaseField() { return Optional.empty(); }

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

                    @Override
                    public Optional<String> tokenFile() {
                        return Optional.empty();
                    }
                });
            }

            @Override
            public Optional<Integer> pageSize() {
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
            public Optional<String> caCert() {
                return Optional.empty();
            }

            @Override
            public Optional<Boolean> insecureSkipTlsVerify() {
                return Optional.empty();
            }

            @Override
            public Optional<String> registry() {
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
            public Optional<String> regex() {
                return Optional.empty();
            }

            @Override
            public Optional<String> host() { return Optional.empty(); }

            @Override
            public Optional<Integer> port() { return Optional.empty(); }

            @Override
            public Optional<String> user() { return Optional.empty(); }

            @Override
            public Optional<String> privateKey() { return Optional.empty(); }

            @Override
            public Optional<String> privateKeyFile() { return Optional.empty(); }

            @Override
            public Optional<String> hostKey() { return Optional.empty(); }

            @Override
            public Optional<String> knownHosts() { return Optional.empty(); }

            @Override
            public Optional<String> releaseField() { return Optional.empty(); }

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

                    @Override
                    public Optional<String> tokenFile() {
                        return Optional.empty();
                    }
                });
            }

            @Override
            public Optional<Integer> pageSize() {
                return Optional.empty();
            }

            @Override
            public Optional<Integer> maxTags() {
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
            public Optional<String> registry() {
                return Optional.empty();
            }

            @Override
            public Optional<String> prereleaseFilter() {
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

    private static String resourcePath(String resource) throws Exception {
        return Path.of(HttpCurrentSourceIT.class.getClassLoader().getResource(resource).toURI()).toString();
    }
}
