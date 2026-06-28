package org.yardship.integration.adapters.out.versionsource.latest.githubrelease;

import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.latest.githubrelease.GithubReleaseLatestSource;
import org.yardship.core.domain.primitives.SemverVersion;
import org.yardship.core.domain.primitives.VersionValue;

import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;

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

    static WireMockServer wireMockServer;

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

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder jsonResponse(int status, String body) {
        return aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body);
    }
}
