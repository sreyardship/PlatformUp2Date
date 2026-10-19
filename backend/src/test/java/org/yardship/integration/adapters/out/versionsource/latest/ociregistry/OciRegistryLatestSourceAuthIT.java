package org.yardship.integration.adapters.out.versionsource.latest.ociregistry;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.latest.ociregistry.OciRegistryLatestSourceFactory;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import org.yardship.core.domain.primitives.VersionValue;
import org.yardship.core.ports.out.LatestVersionSource;
import org.yardship.integration.adapters.out.versionsource.latest.LatestSourceFragment;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Integration tests for the OCI bearer-token dance (ADR-0013), built end to end through
 * {@link OciRegistryLatestSourceFactory#create} against a standalone WireMock server on port 8091
 * (distinct from the no-challenge tests on 8090 in {@link OciRegistryLatestSourceIT}). Plain JUnit.
 *
 * <p>Covers:
 * <ul>
 *   <li>Anonymous dance: 401 challenge → anonymous mint (no Authorization on the token request) →
 *       retry with the minted Bearer → correct version returned.</li>
 *   <li>Basic-into-realm dance: same flow, the token request carrying {@code Authorization: Basic
 *       base64(user:pass)} and the tags retry carrying the Bearer, never the Basic credential.</li>
 *   <li>Challenge echoing: {@code service} and {@code scope} on the token request are verbatim from
 *       the challenge; {@code repository:<repo>:pull} only when the challenge omits {@code scope}.</li>
 *   <li>Token shape: {@code access_token} accepted when {@code token} is absent.</li>
 *   <li>A registry answering 200 directly needs no dance, even with credentials configured.</li>
 *   <li>The authenticated leg fails closed on a non-2xx and paginates like the anonymous one.</li>
 * </ul>
 *
 * <p>How each of these GETs behaves across a redirect — credential retained same-origin only,
 * stripped cross-origin, HTTPS-to-HTTP refused — is the shared transport's contract, proven once
 * in {@code HttpTransportIT}.
 */
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

    private final OciRegistryLatestSourceFactory factory = new OciRegistryLatestSourceFactory();

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

        VersionValue result = anonymousSource().version();

        assertEquals("1.25.3", result.value(),
                "After the bearer dance the source must return the largest clean semver from tags/list");
    }

    @Test
    void anonymousDance_tokenRequest_carriesNoAuthorizationHeader() {
        stubChallenge401();
        stubTokenEndpoint();
        stubTagsListSuccess();

        anonymousSource().version();

        wireMockServer.verify(getRequestedFor(urlPathEqualTo(TOKEN_PATH))
                .withHeader("Authorization", absent()));
    }

    @Test
    void anonymousDance_tokenRequest_echoesServiceAndScopeVerbatimFromChallenge() {
        stubChallenge401();
        stubTokenEndpoint();
        stubTagsListSuccess();

        anonymousSource().version();

        wireMockServer.verify(getRequestedFor(urlPathEqualTo(TOKEN_PATH))
                .withQueryParam("service", equalTo(REGISTRY_SERVICE))
                .withQueryParam("scope", equalTo(CHALLENGE_SCOPE)));
    }

    @Test
    void anonymousDance_retryRequest_carriesBearerToken() {
        stubChallenge401();
        stubTokenEndpoint();
        stubTagsListSuccess();

        anonymousSource().version();

        wireMockServer.verify(getRequestedFor(urlPathEqualTo(TAGS_PATH))
                .withHeader("Authorization", equalTo("Bearer " + MINTED_TOKEN)));
    }

    // ---- basic-into-realm dance ----------------------------------------------------------------

    @Test
    void basicDance_returnsCorrectVersion_afterChallengeMintRetry() {
        stubChallenge401();
        stubTokenEndpointWithBasicAuth("user", "s3cr3t");
        stubTagsListSuccess();

        VersionValue result = basicAuthSource("user", "s3cr3t").version();

        assertEquals("1.25.3", result.value(),
                "After the basic-into-realm dance the source must return the correct version");
    }

    @Test
    void basicDance_tokenRequest_carriesBasicAuthorizationForTheCredentials() {
        stubChallenge401();
        stubTokenEndpointWithBasicAuth("user", "s3cr3t");
        stubTagsListSuccess();

        basicAuthSource("user", "s3cr3t").version();

        wireMockServer.verify(getRequestedFor(urlPathEqualTo(TOKEN_PATH))
                .withHeader("Authorization", equalTo(expectedBasicAuth("user", "s3cr3t"))));
    }

    @Test
    void basicDance_probeRequest_carriesNoCredential_theBasicCredentialIsForTheRealmOnly() {
        stubChallenge401();
        stubTokenEndpointWithBasicAuth("user", "s3cr3t");
        stubTagsListSuccess();

        basicAuthSource("user", "s3cr3t").version();

        wireMockServer.verify(0, getRequestedFor(urlPathEqualTo(TAGS_PATH))
                .withHeader("Authorization", equalTo(expectedBasicAuth("user", "s3cr3t"))));
    }

    @Test
    void basicDance_retryRequest_carriesBearerToken_notBasicCredentials() {
        stubChallenge401();
        stubTokenEndpointWithBasicAuth("user", "s3cr3t");
        stubTagsListSuccess();

        basicAuthSource("user", "s3cr3t").version();

        wireMockServer.verify(getRequestedFor(urlPathEqualTo(TAGS_PATH))
                .withHeader("Authorization", equalTo("Bearer " + MINTED_TOKEN)));
    }

    // ---- token shape: accept both "token" and "access_token" fields ----------------------------

    @Test
    void dance_acceptsAccessTokenField_whenTokenFieldIsAbsent() {
        stubChallenge401();
        wireMockServer.stubFor(get(urlPathEqualTo(TOKEN_PATH))
                .willReturn(jsonResponse(200, """
                        {"access_token": "access-token-xyz"}
                        """)));
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .withHeader("Authorization", equalTo("Bearer access-token-xyz"))
                .willReturn(jsonResponse(200, tagsListBody("1.2.3"))));

        VersionValue result = anonymousSource().version();

        assertEquals("1.2.3", result.value(),
                "access_token field must be accepted as a fallback when token is absent");
    }

    // ---- fallback scope (when registry omits scope from the challenge) -------------------------

    @Test
    void dance_constructsFallbackScope_whenChallengeOmitsScope() {
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

        VersionValue result = anonymousSource().version();

        assertEquals("2.0.0", result.value(),
                "Fallback scope must be used when the registry omits scope from the challenge");
    }

    // ---- no-challenge path still works with an auth-aware source -------------------------------

    @Test
    void noChallengeRegression_directSuccess_stillWorksWithCredentialsConfigured() {
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .willReturn(jsonResponse(200, tagsListBody("3.7.1"))));

        VersionValue result = basicAuthSource("user", "pass").version();

        assertEquals("3.7.1", result.value(),
                "Direct-200 path must still work unchanged when credentials are configured");
        wireMockServer.verify(0, getRequestedFor(urlPathEqualTo(TOKEN_PATH)));
    }

    // ---- authenticated-leg non-2xx and authenticated pagination ------------------------------

    @Test
    void authenticatedTagsRequest_nonSuccessStatus_failsClosed_namingTheStatus() {
        stubChallenge401();
        stubTokenEndpoint();
        // The anonymous probe still gets the 401 (the catch-all above); the Bearer-carrying retry is
        // matched by this MORE SPECIFIC stub and fails.
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .withHeader("Authorization", equalTo("Bearer " + MINTED_TOKEN))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("internal registry error")));

        LatestVersionSource source = anonymousSource();

        IllegalStateException ex = assertThrows(IllegalStateException.class, source::version,
                "a token-minted-but-tags-fail scenario must fail closed rather than mis-parse the body");
        assertEquals("HTTP 500 response: internal registry error", ex.getMessage());
    }

    @Test
    void authenticatedDance_paginatesAcrossTwoPages_andReturnsLargestFromPageTwo() {
        stubChallenge401();
        stubTokenEndpoint();
        String pageOneLink = "<" + TAGS_PATH + "?n=2&last=1.1.0>; rel=\"next\"";
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .withHeader("Authorization", equalTo("Bearer " + MINTED_TOKEN))
                .withQueryParam("n", equalTo("2"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Link", pageOneLink)
                        .withBody(tagsListBody("1.0.0", "1.1.0"))));
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .withHeader("Authorization", equalTo("Bearer " + MINTED_TOKEN))
                .withQueryParam("n", equalTo("2"))
                .withQueryParam("last", equalTo("1.1.0"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(tagsListBody("2.0.0")))); // no Link = last page; largest tag lives here

        VersionValue result = factory.create(fragment().pageSize(2).maxTags(100), SEMVER_PARSER).version();

        assertEquals("2.0.0", result.value(),
                "the authenticated (Bearer) leg must thread Link-header pagination across pages just "
                        + "like the anonymous leg, so the largest tag on page 2 is selected");
        wireMockServer.verify(getRequestedFor(urlPathEqualTo(TAGS_PATH))
                .withHeader("Authorization", equalTo("Bearer " + MINTED_TOKEN))
                .withQueryParam("last", equalTo("1.1.0")));
    }

    // ---- fixtures -------------------------------------------------------------------------------

    private LatestVersionSource anonymousSource() {
        return factory.create(fragment(), SEMVER_PARSER);
    }

    private LatestVersionSource basicAuthSource(String username, String password) {
        return factory.create(fragment().basicAuth(username, password), SEMVER_PARSER);
    }

    private static LatestSourceFragment fragment() {
        return LatestSourceFragment.ofType("oci-registry").registry(BASE_URL).repo(REPO);
    }

    private static String expectedBasicAuth(String username, String password) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

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
        wireMockServer.stubFor(get(urlPathEqualTo(TOKEN_PATH))
                .withHeader("Authorization", equalTo(expectedBasicAuth(username, password)))
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
