package org.yardship.integration.adapters.out.versionsource.latest.ociregistry;

import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.latest.ociregistry.OciRegistryLatestSource;
import org.yardship.adapters.out.versionsource.latest.ociregistry.TagSelection;
import org.yardship.core.domain.primitives.VersionValue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration tests for the OCI bearer-token dance (issue 02) against a standalone WireMock server
 * on port 8091 (distinct from the no-challenge slice-01 tests on port 8090 in
 * {@link OciRegistryLatestSourceIT}).
 *
 * <p>Covers:
 * <ul>
 *   <li>Anonymous dance: 401 challenge → anonymous mint (no Authorization on token request) → retry
 *       with Bearer token → correct version returned.</li>
 *   <li>Basic-into-realm dance: same flow but token request carries {@code Authorization: Basic
 *       base64(user:pass)}.</li>
 *   <li>Challenge echoing: {@code service} and {@code scope} query params on the token request must
 *       be verbatim from the challenge, not constructed.</li>
 *   <li>Regression: a source with basic creds configured still works when the registry responds
 *       directly with 200 (no challenge) — the dance only triggers on 401.</li>
 * </ul>
 *
 * <p>{@code @QuarkusTest} is required because {@link io.quarkus.rest.client.reactive.QuarkusRestClientBuilder}
 * needs a running Quarkus context.
 */
@QuarkusTest
class OciRegistryLatestSourceAuthIT {
    private static final VersionParser SEMVER_PARSER = new VersionParser(VersionScheme.SEMVER);


    static final int PORT = 8091;
    static final String BASE_URL = "http://localhost:" + PORT;
    static final String REGISTRY_SERVICE = "registry.example.com";
    static final String REPO = "library/nginx";
    static final String TAGS_PATH = "/v2/" + REPO + "/tags/list";
    static final String TOKEN_PATH = "/token";
    static final String MINTED_TOKEN = "minted-bearer-xyz";
    static final String CHALLENGE_SCOPE = "repository:library/nginx:pull";

    static WireMockServer wireMockServer;

    private static final TagSelection DEFAULT_SELECTION =
            new TagSelection(100, 1000, Optional.empty(), false);

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(options().port(PORT));
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

    // ---- anonymous dance -----------------------------------------------------------------------

    @Test
    void anonymousDance_returnsCorrectVersion_afterChallengeMintRetry() {
        stubChallenge401();
        stubTokenEndpoint();
        stubTagsListSuccess();

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.empty(), Optional.empty(), DEFAULT_SELECTION, SEMVER_PARSER);

        VersionValue result = latestSource.version();

        assertEquals("1.25.3", result.value(),
                "After the bearer dance the source must return the largest clean semver from tags/list");
    }

    @Test
    void anonymousDance_tokenRequest_carriesNoAuthorizationHeader() {
        stubChallenge401();
        stubTokenEndpoint();
        stubTagsListSuccess();

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.empty(), Optional.empty(), DEFAULT_SELECTION, SEMVER_PARSER);
        latestSource.version();

        // Token mint for anonymous: NO Authorization header must be sent to the realm
        wireMockServer.verify(getRequestedFor(urlPathEqualTo(TOKEN_PATH))
                .withHeader("Authorization", absent()));
    }

    @Test
    void anonymousDance_tokenRequest_echoesServiceAndScopeVerbatimFromChallenge() {
        stubChallenge401();
        stubTokenEndpoint();
        stubTagsListSuccess();

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.empty(), Optional.empty(), DEFAULT_SELECTION, SEMVER_PARSER);
        latestSource.version();

        // The token request must echo the challenge's service and scope exactly
        wireMockServer.verify(getRequestedFor(urlPathEqualTo(TOKEN_PATH))
                .withQueryParam("service", equalTo(REGISTRY_SERVICE))
                .withQueryParam("scope", equalTo(CHALLENGE_SCOPE)));
    }

    @Test
    void anonymousDance_retryRequest_carriesBearerToken() {
        stubChallenge401();
        stubTokenEndpoint();
        stubTagsListSuccess();

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.empty(), Optional.empty(), DEFAULT_SELECTION, SEMVER_PARSER);
        latestSource.version();

        // The retry must carry Authorization: Bearer <minted-token>
        wireMockServer.verify(getRequestedFor(urlPathEqualTo(TAGS_PATH))
                .withHeader("Authorization", equalTo("Bearer " + MINTED_TOKEN)));
    }

    // ---- basic-into-realm dance ----------------------------------------------------------------

    @Test
    void basicDance_returnsCorrectVersion_afterChallengeMintRetry() {
        stubChallenge401();
        stubTokenEndpointWithBasicAuth("user", "s3cr3t");
        stubTagsListSuccess();

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.of("user"), Optional.of("s3cr3t"), DEFAULT_SELECTION, SEMVER_PARSER);

        VersionValue result = latestSource.version();

        assertEquals("1.25.3", result.value(),
                "After the basic-into-realm dance the source must return the correct version");
    }

    @Test
    void basicDance_tokenRequest_carriesBasicAuthorizationForTheCredentials() {
        stubChallenge401();
        stubTokenEndpointWithBasicAuth("user", "s3cr3t");
        stubTagsListSuccess();

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.of("user"), Optional.of("s3cr3t"), DEFAULT_SELECTION, SEMVER_PARSER);
        latestSource.version();

        String expectedBasic = "Basic " + Base64.getEncoder()
                .encodeToString("user:s3cr3t".getBytes(StandardCharsets.UTF_8));
        wireMockServer.verify(getRequestedFor(urlPathEqualTo(TOKEN_PATH))
                .withHeader("Authorization", equalTo(expectedBasic)));
    }

    @Test
    void basicDance_tokenRequest_echoesServiceAndScopeVerbatimFromChallenge() {
        stubChallenge401();
        stubTokenEndpointWithBasicAuth("user", "s3cr3t");
        stubTagsListSuccess();

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.of("user"), Optional.of("s3cr3t"), DEFAULT_SELECTION, SEMVER_PARSER);
        latestSource.version();

        wireMockServer.verify(getRequestedFor(urlPathEqualTo(TOKEN_PATH))
                .withQueryParam("service", equalTo(REGISTRY_SERVICE))
                .withQueryParam("scope", equalTo(CHALLENGE_SCOPE)));
    }

    @Test
    void basicDance_retryRequest_carriesBearerToken_notBasicCredentials() {
        stubChallenge401();
        stubTokenEndpointWithBasicAuth("user", "s3cr3t");
        stubTagsListSuccess();

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.of("user"), Optional.of("s3cr3t"), DEFAULT_SELECTION, SEMVER_PARSER);
        latestSource.version();

        // The retry on tags/list must carry the minted Bearer token, NOT the basic credentials
        wireMockServer.verify(getRequestedFor(urlPathEqualTo(TAGS_PATH))
                .withHeader("Authorization", equalTo("Bearer " + MINTED_TOKEN)));
    }

    // ---- token shape: accept both "token" and "access_token" fields ----------------------------

    @Test
    void dance_acceptsAccessTokenField_whenTokenFieldIsAbsent() {
        stubChallenge401();
        // Some registries return "access_token" instead of "token"
        wireMockServer.stubFor(get(urlPathEqualTo(TOKEN_PATH))
                .willReturn(jsonResponse(200, """
                        {"access_token": "access-token-xyz"}
                        """)));
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .withHeader("Authorization", equalTo("Bearer access-token-xyz"))
                .willReturn(jsonResponse(200, tagsListBody("1.2.3"))));

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.empty(), Optional.empty(), DEFAULT_SELECTION, SEMVER_PARSER);

        VersionValue result = latestSource.version();

        assertEquals("1.2.3", result.value(),
                "access_token field must be accepted as a fallback when token is absent");
    }

    // ---- fallback scope (when registry omits scope from the challenge) -------------------------

    @Test
    void dance_constructsFallbackScope_whenChallengeOmitsScope() {
        // A challenge without a scope field — the source must fall back to
        // "repository:<repo>:pull" instead of failing.
        String challengeWithoutScope = "Bearer realm=\"" + BASE_URL + TOKEN_PATH
                + "\",service=\"" + REGISTRY_SERVICE + "\"";
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("WWW-Authenticate", challengeWithoutScope)));

        String constructedScope = "repository:" + REPO + ":pull";
        wireMockServer.stubFor(get(urlPathEqualTo(TOKEN_PATH))
                .withQueryParam("scope", equalTo(constructedScope))
                .willReturn(jsonResponse(200, """
                        {"token": "fallback-scope-token"}
                        """)));
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .withHeader("Authorization", equalTo("Bearer fallback-scope-token"))
                .willReturn(jsonResponse(200, tagsListBody("2.0.0"))));

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.empty(), Optional.empty(), DEFAULT_SELECTION, SEMVER_PARSER);

        VersionValue result = latestSource.version();

        assertEquals("2.0.0", result.value(),
                "Fallback scope must be used when the registry omits scope from the challenge");
    }

    // ---- regression: no-challenge path (slice 01) still works with auth-aware source -----------

    @Test
    void noChallengeRegression_directSuccess_stillWorksWithAuthAwareSource() {
        // When the registry responds directly with 200 (no 401 challenge), the dance must not
        // trigger — the source returns the version directly. This validates backward-compat
        // with the anonymous-access registries tested in OciRegistryLatestSourceIT (port 8090).
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .willReturn(jsonResponse(200, tagsListBody("3.7.1"))));

        // Even with basic creds configured, no dance if no 401 challenge
        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.of("user"), Optional.of("pass"), DEFAULT_SELECTION, SEMVER_PARSER);

        VersionValue result = latestSource.version();

        assertEquals("3.7.1", result.value(),
                "Direct-200 path must still work unchanged after the bearer-dance feature is added");
    }

    // ---- stub helpers -------------------------------------------------------------------------

    private void stubChallenge401() {
        String wwwAuthenticate = "Bearer realm=\"" + BASE_URL + TOKEN_PATH
                + "\",service=\"" + REGISTRY_SERVICE
                + "\",scope=\"" + CHALLENGE_SCOPE + "\"";
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("WWW-Authenticate", wwwAuthenticate)));
    }

    private void stubTokenEndpoint() {
        wireMockServer.stubFor(get(urlPathEqualTo(TOKEN_PATH))
                .willReturn(jsonResponse(200, """
                        {"token": "%s"}
                        """.formatted(MINTED_TOKEN))));
    }

    private void stubTokenEndpointWithBasicAuth(String username, String password) {
        String basic = "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        wireMockServer.stubFor(get(urlPathEqualTo(TOKEN_PATH))
                .withHeader("Authorization", equalTo(basic))
                .willReturn(jsonResponse(200, """
                        {"token": "%s"}
                        """.formatted(MINTED_TOKEN))));
    }

    private void stubTagsListSuccess() {
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .withHeader("Authorization", equalTo("Bearer " + MINTED_TOKEN))
                .willReturn(jsonResponse(200, tagsListBody("1.25.3", "1.24.0", "latest"))));
    }

    private static String tagsListBody(String... tags) {
        String joined = String.join("\", \"", tags);
        return """
                {"name": "%s", "tags": ["%s"]}
                """.formatted(REPO, joined);
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder jsonResponse(
            int status, String body) {
        return aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body);
    }
}
