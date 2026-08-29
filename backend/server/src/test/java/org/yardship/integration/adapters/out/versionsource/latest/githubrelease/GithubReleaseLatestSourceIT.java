package org.yardship.integration.adapters.out.versionsource.latest.githubrelease;

import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.yardship.adapters.out.versionsource.VersionFetchException;
import org.yardship.adapters.out.versionsource.latest.githubrelease.GithubReleaseLatestSource;
import org.yardship.core.domain.primitives.SemverVersion;
import org.yardship.core.domain.primitives.VersionValue;

import java.io.InputStream;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Optional;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Integration test for the real {@link GithubReleaseLatestSource} adapter against a standalone
 * WireMock server on port 8089. {@code GithubReleaseLatestSource} wraps the existing
 * {@link org.yardship.adapters.out.versionsource.latest.githubrelease.GithubReleaseClient} REST client and OWNS the
 * GitHub auth concern: when constructed with a token it registers the shared, scheme-generic
 * {@link org.yardship.adapters.out.versionsource.auth.BearerAuthFilter} so the latest leg carries
 * {@code Authorization: Bearer <token>}; when constructed without one it sends no auth header.
 *
 * <p><b>This issue retargets the adapter from GitHub's time-ordered {@code GET /releases/latest}
 * (single object) to {@code GET /releases} (array), selecting the maximum semver among
 * {@code prerelease == false && draft == false} releases by {@code tag_name} — see ADR-0010. The
 * stub endpoint below is therefore {@code /releases} (an array), not {@code /latest} (an object), and
 * every stubbed release JSON object now carries {@code tag_name}/{@code prerelease}/{@code draft} —
 * real Jackson/JSON-B deserialization of those three new {@link
 * org.yardship.adapters.out.versionsource.latest.githubrelease.GithubReleaseResponseDTO} fields is exercised here, not
 * just hand-built fakes (see {@code GithubReleaseLatestSourceTests} for the pure-selection-logic unit
 * coverage via a fake client).</b>
 *
 * <p>The default {@code GithubReleaseLatestSource(String, Optional<String>)} constructor is assumed
 * to keep defaulting {@code page-size} to 30 (the factory's default; this adapter-level constructor
 * itself defaults the wire-level {@code per_page} the same way when not told otherwise — see the
 * dedicated {@code per_page} assertion below, which pins 30 as the default sent on the wire when this
 * 2-arg constructor is used). If the implementer instead threads page-size through a 3rd constructor
 * argument, only the {@code read_sendsConfiguredPerPage_asQueryParam} test below needs to change to
 * use that constructor — the rest of this suite is agnostic to that choice.
 *
 * <p>{@code @QuarkusTest} is used because {@code QuarkusRestClientBuilder} needs a running Quarkus
 * context — matching the existing IT style. The source is constructed directly (plain object) with
 * a base URL plus an {@link Optional} token.
 */
@QuarkusTest
class GithubReleaseLatestSourceIT {
    private static final VersionParser SEMVER_PARSER = new VersionParser(VersionScheme.SEMVER);
    private static final String KEYSTORE_RESOURCE = "tls/wiremock-localhost.p12";
    private static final String KEYSTORE_PASSWORD = "password";

    static WireMockServer wireMockServer;
    static WireMockServer crossOriginWireMockServer;
    static WireMockServer httpsWireMockServer;

    @BeforeAll
    static void startWireMock() throws Exception {
        wireMockServer = new WireMockServer(options().port(8089));
        wireMockServer.start();
        crossOriginWireMockServer = new WireMockServer(options().port(8091));
        crossOriginWireMockServer.start();
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
    void read_selectsTheLargestSemver_byTagName_amongNonPrereleaseNonDraftReleases() {
        wireMockServer.stubFor(get(urlPathEqualTo("/releases"))
                .willReturn(jsonResponse(200, """
                        [
                          {"tag_name":"v1.2.0","name":"older","prerelease":false,"draft":false},
                          {"tag_name":"v2.0.0","name":"newest-numerically","prerelease":false,"draft":false},
                          {"tag_name":"v1.9.0","name":"middle","prerelease":false,"draft":false}
                        ]
                        """)));

        GithubReleaseLatestSource source =
                new GithubReleaseLatestSource("http://localhost:8089", Optional.empty(), SEMVER_PARSER);

        VersionValue result = source.version();

        assertEquals("2.0.0", result.value(),
                "the 'v' prefix is trimmed by the Version primitive; selection is by tag_name, not name");
    }

    @Test
    void read_followsRepositoryMoveRedirect_andSelectsLargestEligibleVersionFromFinalArray() {
        wireMockServer.stubFor(get(urlEqualTo("/repos/vmware-tanzu/velero/releases?per_page=30"))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", "/repositories/99143276/releases?per_page=30")));
        wireMockServer.stubFor(get(urlEqualTo("/repositories/99143276/releases?per_page=30"))
                .willReturn(jsonResponse(200, """
                        [
                          {"tag_name":"v1.5.0","prerelease":false,"draft":false},
                          {"tag_name":"v2.0.0","prerelease":false,"draft":false},
                          {"tag_name":"v9.0.0","prerelease":true,"draft":false},
                          {"tag_name":"v8.0.0","prerelease":false,"draft":true}
                        ]
                        """)));

        GithubReleaseLatestSource source = new GithubReleaseLatestSource(
                "http://localhost:8089/repos/vmware-tanzu/velero", Optional.empty(), SEMVER_PARSER);

        VersionValue result = source.version();

        assertEquals("2.0.0", result.value());
        wireMockServer.verify(getRequestedFor(urlEqualTo("/repositories/99143276/releases?per_page=30")));
    }

    @Test
    void read_twice_traversesPermanentRedirectTwice_withoutCachingDestination() {
        String sourcePath = "/repos/vmware-tanzu/velero/releases?per_page=30";
        String destinationPath = "/repositories/99143276/releases?per_page=30";
        wireMockServer.stubFor(get(urlEqualTo(sourcePath))
                .willReturn(aResponse().withStatus(301).withHeader("Location", destinationPath)));
        wireMockServer.stubFor(get(urlEqualTo(destinationPath))
                .willReturn(jsonResponse(200, "[{\"tag_name\":\"v2.0.0\",\"prerelease\":false,\"draft\":false}]")));

        GithubReleaseLatestSource source = new GithubReleaseLatestSource(
                "http://localhost:8089/repos/vmware-tanzu/velero", Optional.empty(), SEMVER_PARSER);

        assertEquals("2.0.0", source.version().value());
        assertEquals("2.0.0", source.version().value());

        wireMockServer.verify(2, getRequestedFor(urlEqualTo(sourcePath)));
        wireMockServer.verify(2, getRequestedFor(urlEqualTo(destinationPath)));
    }

    @ParameterizedTest(name = "status {0} with {1} Location")
    @MethodSource("supportedGetRedirects")
    void read_followsSupportedGetRedirects_forRelativeAndAbsoluteLocations(int status, String locationKind) {
        wireMockServer.resetAll();
        String location = "relative".equals(locationKind)
                ? "/repositories/99143276/releases?per_page=30"
                : "http://localhost:8089/repositories/99143276/releases?per_page=30";
        wireMockServer.stubFor(get(urlEqualTo("/repos/vmware-tanzu/velero/releases?per_page=30"))
                .willReturn(aResponse().withStatus(status).withHeader("Location", location)));
        wireMockServer.stubFor(get(urlEqualTo("/repositories/99143276/releases?per_page=30"))
                .willReturn(jsonResponse(200, "[{\"tag_name\":\"v2.0.0\",\"prerelease\":false,\"draft\":false}]")));

        GithubReleaseLatestSource source = new GithubReleaseLatestSource(
                "http://localhost:8089/repos/vmware-tanzu/velero", Optional.empty(), SEMVER_PARSER);

        assertEquals("2.0.0", source.version().value());
        wireMockServer.verify(getRequestedFor(urlEqualTo("/repositories/99143276/releases?per_page=30")));
    }

    private static Stream<Arguments> supportedGetRedirects() {
        return Stream.of(301, 302, 303, 307, 308)
                .flatMap(status -> Stream.of(
                        Arguments.of(status, "relative"),
                        Arguments.of(status, "absolute")));
    }

    @Test
    void read_redirectLoop_terminatesAsSourceReadFailureAfterBoundedHops() {
        String releasesPath = "/repos/vmware-tanzu/velero/releases?per_page=30";
        wireMockServer.stubFor(get(urlEqualTo(releasesPath))
                .willReturn(aResponse().withStatus(301).withHeader("Location", releasesPath)));

        GithubReleaseLatestSource source = new GithubReleaseLatestSource(
                "http://localhost:8089/repos/vmware-tanzu/velero", Optional.empty(), SEMVER_PARSER);

        assertThrows(RuntimeException.class, source::version);
        wireMockServer.verify(11, getRequestedFor(urlEqualTo(releasesPath)));
    }

    @Test
    void read_mapsFinalNon2xxAfterRedirect_toExistingSourceFailure() {
        String initialPath = "/repos/vmware-tanzu/velero/releases?per_page=30";
        String finalPath = "/repositories/99143276/releases?per_page=30";
        wireMockServer.stubFor(get(urlEqualTo(initialPath))
                .willReturn(aResponse().withStatus(301).withHeader("Location", finalPath)));
        wireMockServer.stubFor(get(urlEqualTo(finalPath))
                .willReturn(aResponse().withStatus(404).withBody("repository not found")));

        GithubReleaseLatestSource source = new GithubReleaseLatestSource(
                "http://localhost:8089/repos/vmware-tanzu/velero", Optional.empty(), SEMVER_PARSER);

        VersionFetchException failure = assertThrows(VersionFetchException.class, source::version);

        assertEquals(404, failure.status());
        assertEquals("repository not found", failure.body());
        wireMockServer.verify(1, getRequestedFor(urlEqualTo(initialPath)));
        wireMockServer.verify(1, getRequestedFor(urlEqualTo(finalPath)));
    }

    @Test
    void read_excludesPrereleaseAndDraftReleases_evenIfNumericallyLarger() {
        wireMockServer.stubFor(get(urlPathEqualTo("/releases"))
                .willReturn(jsonResponse(200, """
                        [
                          {"tag_name":"v1.0.0","name":"stable","prerelease":false,"draft":false},
                          {"tag_name":"v9.0.0","name":"a-prerelease","prerelease":true,"draft":false},
                          {"tag_name":"v8.0.0","name":"a-draft","prerelease":false,"draft":true}
                        ]
                        """)));

        GithubReleaseLatestSource source =
                new GithubReleaseLatestSource("http://localhost:8089", Optional.empty(), SEMVER_PARSER);

        VersionValue result = source.version();

        assertEquals("1.0.0", result.value());
    }

    @Test
    void read_sendsPerPageQueryParam_defaultingTo30() {
        wireMockServer.stubFor(get(urlPathEqualTo("/releases"))
                .willReturn(jsonResponse(200, """
                        [{"tag_name":"v1.0.0","name":"n","prerelease":false,"draft":false}]
                        """)));

        GithubReleaseLatestSource source =
                new GithubReleaseLatestSource("http://localhost:8089", Optional.empty(), SEMVER_PARSER);

        source.version();

        wireMockServer.verify(getRequestedFor(urlPathEqualTo("/releases"))
                .withQueryParam("per_page", equalTo("30")));
    }

    @Test
    void read_sendsBearerToken_whenConstructedWithAToken() {
        wireMockServer.stubFor(get(urlEqualTo("/repos/vmware-tanzu/velero/releases?per_page=30"))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", "/repositories/99143276/releases?per_page=30")));
        wireMockServer.stubFor(get(urlEqualTo("/repositories/99143276/releases?per_page=30"))
                .willReturn(jsonResponse(200, """
                        [{"tag_name":"v2.0.0","name":"n","prerelease":false,"draft":false}]
                        """)));

        GithubReleaseLatestSource source = new GithubReleaseLatestSource(
                "http://localhost:8089/repos/vmware-tanzu/velero", Optional.of("test-token"), SEMVER_PARSER);

        source.version();

        wireMockServer.verify(getRequestedFor(urlEqualTo("/repos/vmware-tanzu/velero/releases?per_page=30"))
                .withHeader("Authorization", equalTo("Bearer test-token")));
        wireMockServer.verify(getRequestedFor(urlEqualTo("/repositories/99143276/releases?per_page=30"))
                .withHeader("Authorization", equalTo("Bearer test-token")));
    }

    @Test
    void read_refusesHttpsToHttpRedirect_beforeContactingHttpTarget() throws Exception {
        String redirectedPath = "/repositories/99143276/releases?per_page=30";
        httpsWireMockServer.stubFor(get(urlEqualTo("/repos/vmware-tanzu/velero/releases?per_page=30"))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", "http://localhost:8089" + redirectedPath)));
        wireMockServer.stubFor(get(urlEqualTo(redirectedPath))
                .willReturn(jsonResponse(200, "[{\"tag_name\":\"v2.0.0\",\"prerelease\":false,\"draft\":false}]")));

        GithubReleaseLatestSource source = new GithubReleaseLatestSource(
                "https://localhost:" + httpsWireMockServer.httpsPort() + "/repos/vmware-tanzu/velero",
                Optional.empty(), Optional.of(trustStoreHoldingWireMockCa()), SEMVER_PARSER);

        assertThrows(RuntimeException.class, source::version);

        httpsWireMockServer.verify(getRequestedFor(urlEqualTo(
                "/repos/vmware-tanzu/velero/releases?per_page=30")));
        wireMockServer.verify(0, getRequestedFor(urlEqualTo(redirectedPath)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("effectiveOriginChanges")
    void read_doesNotForwardAuthorization_whenRedirectChangesEffectiveOrigin(
            String change, String location, WireMockServer targetServer, Optional<KeyStore> trustStore) {
        String initialPath = "/repos/vmware-tanzu/velero/releases?per_page=30";
        String destinationPath = "/repositories/99143276/releases?per_page=30";
        wireMockServer.stubFor(get(urlEqualTo(initialPath))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", location)));
        targetServer.stubFor(get(urlEqualTo(destinationPath))
                .willReturn(jsonResponse(200, "[{\"tag_name\":\"v2.0.0\",\"prerelease\":false,\"draft\":false}]")));

        GithubReleaseLatestSource source = new GithubReleaseLatestSource(
                "http://localhost:8089/repos/vmware-tanzu/velero", Optional.of("test-token"),
                trustStore, SEMVER_PARSER);

        assertEquals("2.0.0", source.version().value());
        wireMockServer.verify(getRequestedFor(urlEqualTo(initialPath))
                .withHeader("Authorization", equalTo("Bearer test-token")));
        targetServer.verify(getRequestedFor(urlEqualTo(destinationPath))
                .withHeader("Authorization", absent()));
    }

    private static Stream<Arguments> effectiveOriginChanges() throws Exception {
        String destination = "/repositories/99143276/releases?per_page=30";
        return Stream.of(
                Arguments.of("host", "http://127.0.0.1:8089" + destination,
                        wireMockServer, Optional.empty()),
                Arguments.of("effective port", "http://localhost:8091" + destination,
                        crossOriginWireMockServer, Optional.empty()),
                Arguments.of("scheme", "https://localhost:" + httpsWireMockServer.httpsPort() + destination,
                        httpsWireMockServer, Optional.of(trustStoreHoldingWireMockCa())));
    }

    @Test
    void read_omitsAuthorizationHeader_whenConstructedWithoutAToken() {
        wireMockServer.stubFor(get(urlPathEqualTo("/releases"))
                .willReturn(jsonResponse(200, """
                        [{"tag_name":"v2.0.0","name":"n","prerelease":false,"draft":false}]
                        """)));

        GithubReleaseLatestSource source =
                new GithubReleaseLatestSource("http://localhost:8089", Optional.empty(), SEMVER_PARSER);

        source.version();

        wireMockServer.verify(getRequestedFor(urlPathEqualTo("/releases"))
                .withHeader("Authorization", absent()));
    }

    @Test
    void read_omitsAuthorizationHeader_whenTokenIsBlank() {
        // A blank token must be treated as "no auth" — the filter must not be registered.
        wireMockServer.stubFor(get(urlPathEqualTo("/releases"))
                .willReturn(jsonResponse(200, """
                        [{"tag_name":"v2.0.0","name":"n","prerelease":false,"draft":false}]
                        """)));

        GithubReleaseLatestSource source =
                new GithubReleaseLatestSource("http://localhost:8089", Optional.of("   "), SEMVER_PARSER);

        source.version();

        wireMockServer.verify(getRequestedFor(urlPathEqualTo("/releases"))
                .withHeader("Authorization", absent()));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder jsonResponse(int status, String body) {
        return aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body);
    }

    private static String resourcePath(String resource) throws Exception {
        return Path.of(GithubReleaseLatestSourceIT.class.getClassLoader()
                .getResource(resource).toURI()).toString();
    }

    private static KeyStore trustStoreHoldingWireMockCa() throws Exception {
        KeyStore wireMockKeystore = KeyStore.getInstance("PKCS12");
        try (InputStream in = GithubReleaseLatestSourceIT.class.getClassLoader()
                .getResourceAsStream(KEYSTORE_RESOURCE)) {
            wireMockKeystore.load(in, KEYSTORE_PASSWORD.toCharArray());
        }
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        trustStore.setCertificateEntry("wiremock-ca", wireMockKeystore.getCertificate("wiremock"));
        return trustStore;
    }
}
