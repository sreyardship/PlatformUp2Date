package org.yardship.integration.adapters.out.versionsource.latest.githubrelease;

import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
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

import java.time.Duration;
import java.util.Optional;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.moreThanOrExactly;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
@TestProfile(GithubReleaseRedirectAuthorizationTestProfile.class)
class GithubReleaseLatestSourceIT {
    private static final VersionParser SEMVER_PARSER = new VersionParser(VersionScheme.SEMVER);
    private static final String KEYSTORE_RESOURCE = "tls/wiremock-localhost.p12";
    private static final String KEYSTORE_PASSWORD = "password";

    static WireMockServer wireMockServer;
    static WireMockServer crossOriginWireMockServer;

    @BeforeAll
    static void startWireMock() throws Exception {
        wireMockServer = new WireMockServer(options().port(8089));
        wireMockServer.start();
        crossOriginWireMockServer = new WireMockServer(options()
                .dynamicPort()
                .dynamicHttpsPort()
                .keystorePath(resourcePath(KEYSTORE_RESOURCE))
                .keystorePassword(KEYSTORE_PASSWORD)
                .keyManagerPassword(KEYSTORE_PASSWORD)
                .keystoreType("PKCS12"));
        crossOriginWireMockServer.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMockServer.stop();
        crossOriginWireMockServer.stop();
    }

    @BeforeEach
    void resetStubs() {
        wireMockServer.resetAll();
        crossOriginWireMockServer.resetAll();
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

    @ParameterizedTest(name = "{0} with {1} Location")
    @MethodSource("repositoryMoveRedirects")
    void read_followsRepositoryMoveRedirect_andSelectsLargestEligibleRelease(
            int status, String locationKind) {
        String initialPath = "/repos/vmware-tanzu/velero/releases?per_page=30";
        String movedPath = "/repositories/99143276/releases?per_page=30";
        String location = locationKind.equals("absolute") ? wireMockServer.baseUrl() + movedPath : movedPath;
        wireMockServer.stubFor(get(urlEqualTo(initialPath))
                .willReturn(aResponse().withStatus(status).withHeader("Location", location)));
        wireMockServer.stubFor(get(urlEqualTo(movedPath))
                .willReturn(jsonResponse(200, """
                        [
                          {"tag_name":"v1.0.0","prerelease":false,"draft":false},
                          {"tag_name":"v2.0.0","prerelease":true,"draft":false},
                          {"tag_name":"v3.0.0","prerelease":false,"draft":true},
                          {"tag_name":"v1.5.0","prerelease":false,"draft":false}
                        ]
                        """)));

        GithubReleaseLatestSource source =
                new GithubReleaseLatestSource("http://localhost:8089/repos/vmware-tanzu/velero",
                        Optional.empty(), SEMVER_PARSER);

        VersionValue result = source.version();

        assertEquals("1.5.0", result.value());
        wireMockServer.verify(getRequestedFor(urlEqualTo(initialPath)));
        wireMockServer.verify(getRequestedFor(urlEqualTo(movedPath)));
    }

    private static Stream<Arguments> repositoryMoveRedirects() {
        return Stream.of(301, 302, 303, 307, 308)
                .flatMap(status -> Stream.of("relative", "absolute")
                        .map(locationKind -> Arguments.of(status, locationKind)));
    }

    @ParameterizedTest(name = "{0} redirect reports the final non-2xx response")
    @MethodSource("supportedRedirectStatuses")
    void read_mapsFinalNon2xxResponse_afterFollowingRedirect(int redirectStatus) {
        String initialPath = "/redirecting-source/releases?per_page=30";
        String finalPath = "/unavailable-source/releases?per_page=30";
        String finalBody = "{\"message\":\"final upstream unavailable\"}";
        wireMockServer.stubFor(get(urlEqualTo(initialPath))
                .willReturn(aResponse().withStatus(redirectStatus)
                        .withHeader("Location", finalPath)
                        .withBody("intermediate redirect response must not be reported")));
        wireMockServer.stubFor(get(urlEqualTo(finalPath))
                .willReturn(jsonResponse(503, finalBody)));
        GithubReleaseLatestSource source = new GithubReleaseLatestSource(
                "http://localhost:8089/redirecting-source", Optional.empty(), SEMVER_PARSER);

        VersionFetchException exception = assertThrows(VersionFetchException.class, source::version);

        assertEquals(503, exception.status());
        assertEquals(finalBody, exception.body());
        wireMockServer.verify(getRequestedFor(urlEqualTo(initialPath)));
        wireMockServer.verify(getRequestedFor(urlEqualTo(finalPath)));
    }

    private static Stream<Arguments> supportedRedirectStatuses() {
        return Stream.of(301, 302, 303, 307, 308).map(Arguments::of);
    }

    @ParameterizedTest(name = "{0} permanent redirect is traversed for each scrape")
    @MethodSource("permanentRedirectStatuses")
    void read_doesNotCachePermanentRedirectDestination_betweenScrapes(int status) {
        String initialPath = "/repos/vmware-tanzu/velero/releases?per_page=30";
        String movedPath = "/repositories/99143276/releases?per_page=30";
        wireMockServer.stubFor(get(urlEqualTo(initialPath))
                .willReturn(aResponse().withStatus(status).withHeader("Location", movedPath)));
        wireMockServer.stubFor(get(urlEqualTo(movedPath))
                .willReturn(jsonResponse(200, """
                        [{"tag_name":"v1.5.0","prerelease":false,"draft":false}]
                        """)));
        GithubReleaseLatestSource source = new GithubReleaseLatestSource(
                "http://localhost:8089/repos/vmware-tanzu/velero", Optional.empty(), SEMVER_PARSER);

        assertEquals("1.5.0", source.version().value());
        assertEquals("1.5.0", source.version().value());

        wireMockServer.verify(2, getRequestedFor(urlEqualTo(initialPath)));
        wireMockServer.verify(2, getRequestedFor(urlEqualTo(movedPath)));
    }

    private static Stream<Arguments> permanentRedirectStatuses() {
        return Stream.of(Arguments.of(301), Arguments.of(308));
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

    @ParameterizedTest(name = "{0} redirect {1} the bearer token")
    @MethodSource("redirectAuthorizationOrigins")
    void read_retainsBearerTokenOnlyWhenRedirectTargetHasTheSameOrigin(
            String targetDescription, boolean retainsAuthorization) {
        String initialPath = "/redirect-origin/releases?per_page=30";
        String targetPath = "/redirect-target/releases?per_page=30";
        String targetUrl = switch (targetDescription) {
            case "same origin" -> wireMockServer.baseUrl() + targetPath;
            case "changed host" -> "http://127.0.0.1:8089" + targetPath;
            case "changed effective port" -> crossOriginWireMockServer.baseUrl() + targetPath;
            case "changed scheme" -> "https://localhost:" + crossOriginWireMockServer.httpsPort() + targetPath;
            default -> throw new IllegalArgumentException("Unknown redirect target: " + targetDescription);
        };
        WireMockServer targetServer = targetDescription.equals("same origin")
                || targetDescription.equals("changed host") ? wireMockServer : crossOriginWireMockServer;

        wireMockServer.stubFor(get(urlEqualTo(initialPath))
                .willReturn(aResponse().withStatus(301).withHeader("Location", targetUrl)));
        targetServer.stubFor(get(urlEqualTo(targetPath))
                .willReturn(jsonResponse(200, """
                        [{"tag_name":"v2.0.0","prerelease":false,"draft":false}]
                        """)));

        GithubReleaseLatestSource source = new GithubReleaseLatestSource(
                "http://localhost:8089/redirect-origin", Optional.of("test-token"), SEMVER_PARSER);

        assertEquals("2.0.0", source.version().value());
        wireMockServer.verify(getRequestedFor(urlEqualTo(initialPath))
                .withHeader("Authorization", equalTo("Bearer test-token")));
        targetServer.verify(1, getRequestedFor(urlEqualTo(targetPath)));
        targetServer.verify(getRequestedFor(urlEqualTo(targetPath)).withHeader(
                "Authorization", retainsAuthorization ? equalTo("Bearer test-token") : absent()));
    }

    private static Stream<Arguments> redirectAuthorizationOrigins() {
        return Stream.of(
                Arguments.of("same origin", true),
                Arguments.of("changed host", false),
                Arguments.of("changed effective port", false),
                Arguments.of("changed scheme", false));
    }

    @Test
    void read_refusesHttpsToHttpRedirect_beforeContactingTarget() {
        String initialPath = "/secure-origin/releases?per_page=30";
        String targetPath = "/insecure-target/releases?per_page=30";
        String insecureTarget = "http://localhost:" + crossOriginWireMockServer.port() + targetPath;
        crossOriginWireMockServer.stubFor(get(urlEqualTo(initialPath))
                .willReturn(aResponse().withStatus(301).withHeader("Location", insecureTarget)));
        crossOriginWireMockServer.stubFor(get(urlEqualTo(targetPath))
                .willReturn(jsonResponse(200, """
                        [{"tag_name":"2.0.0","prerelease":false,"draft":false}]
                        """)));

        GithubReleaseLatestSource source = new GithubReleaseLatestSource(
                "https://localhost:" + crossOriginWireMockServer.httpsPort() + "/secure-origin",
                Optional.empty(), SEMVER_PARSER);

        assertThrows(RuntimeException.class, source::version);
        crossOriginWireMockServer.verify(1, getRequestedFor(urlEqualTo(initialPath)));
        crossOriginWireMockServer.verify(0, getRequestedFor(urlEqualTo(targetPath)));
    }

    @Test
    void read_redirectLoop_terminatesAsASourceReadFailure() {
        String firstPath = "/redirect-loop/first/releases?per_page=30";
        String secondPath = "/redirect-loop/second/releases?per_page=30";
        wireMockServer.stubFor(get(urlEqualTo(firstPath))
                .willReturn(aResponse().withStatus(301).withHeader("Location", secondPath)));
        wireMockServer.stubFor(get(urlEqualTo(secondPath))
                .willReturn(aResponse().withStatus(301).withHeader("Location", firstPath)));
        GithubReleaseLatestSource source = new GithubReleaseLatestSource(
                "http://localhost:8089/redirect-loop/first", Optional.empty(), SEMVER_PARSER);

        assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> assertThrows(RuntimeException.class, source::version));
        wireMockServer.verify(moreThanOrExactly(2), getRequestedFor(urlEqualTo(firstPath)));
        wireMockServer.verify(moreThanOrExactly(1), getRequestedFor(urlEqualTo(secondPath)));
        assertTrue(wireMockServer.getAllServeEvents().size() <= 17,
                "the REST client stops after its bounded redirect limit rather than following the loop indefinitely");
    }

    @Test
    void read_sendsBearerToken_whenConstructedWithAToken() {
        wireMockServer.stubFor(get(urlPathEqualTo("/releases"))
                .willReturn(jsonResponse(200, """
                        [{"tag_name":"v2.0.0","name":"n","prerelease":false,"draft":false}]
                        """)));

        GithubReleaseLatestSource source =
                new GithubReleaseLatestSource("http://localhost:8089", Optional.of("test-token"), SEMVER_PARSER);

        source.version();

        wireMockServer.verify(getRequestedFor(urlPathEqualTo("/releases"))
                .withHeader("Authorization", equalTo("Bearer test-token")));
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

    private static String resourcePath(String resource) throws Exception {
        return java.nio.file.Path.of(GithubReleaseLatestSourceIT.class.getClassLoader()
                .getResource(resource).toURI()).toString();
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder jsonResponse(int status, String body) {
        return aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body);
    }
}
