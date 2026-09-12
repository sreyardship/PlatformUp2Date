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

import java.net.URLEncoder;
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
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Integration tests for the OCI bearer-token dance against a standalone WireMock server
 * on port 8091 (distinct from the no-challenge tests on port 8090 in
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

    // ---- redirect fixtures -------------------------------------------------------------------
    // A second, independently-hosted plain-HTTP server standing in for a different origin (distinct
    // port from the primary 8091) — used only by the cross-origin-authorization tests below: a
    // redirect Location pointing here has a different effective port than the request that produced
    // it, so per ADR-0029 neither the configured Basic credential (token leg) nor the minted Bearer
    // token (authenticated-tags leg) may be forwarded to it.
    static final int CROSS_ORIGIN_PORT = 8095;
    static WireMockServer crossOriginWireMockServer;

    // An HTTPS-only server (self-signed CN=localhost cert, same fixture as HttpJsonCurrentSourceTlsIT /
    // HttpJsonCurrentSourceRedirectIT) used only by the HTTPS-to-HTTP downgrade-refusal tests below.
    // Deliberately untrusted: OciRegistryLatestSource has no truststore/insecure-skip-tls-verify
    // configuration knob (unlike the `http-json` current source), so any contact with this server fails
    // TLS trust regardless of hop position. That is fine for these tests — the property under test
    // is "the plain-HTTP downgrade target is never contacted", which holds whether the call fails at
    // the TLS-trust step or at an explicit downgrade refusal (see the per-test comments).
    private static final String TLS_KEYSTORE_RESOURCE = "tls/wiremock-localhost.p12";
    private static final String TLS_KEYSTORE_PASSWORD = "password";
    static WireMockServer httpsWireMockServer;
    static String httpsBaseUrl;

    private static final TagSelection DEFAULT_SELECTION =
            new TagSelection(100, 1000, Optional.empty(), false);

    @BeforeAll
    static void startWireMock() throws Exception {
        wireMockServer = new WireMockServer(options().port(PORT));
        wireMockServer.start();

        crossOriginWireMockServer = new WireMockServer(options().port(CROSS_ORIGIN_PORT));
        crossOriginWireMockServer.start();

        String keystorePath = java.nio.file.Path.of(OciRegistryLatestSourceAuthIT.class.getClassLoader()
                .getResource(TLS_KEYSTORE_RESOURCE).toURI()).toString();
        httpsWireMockServer = new WireMockServer(options()
                .httpDisabled(true)
                .dynamicHttpsPort()
                .keystorePath(keystorePath)
                .keystorePassword(TLS_KEYSTORE_PASSWORD)
                .keyManagerPassword(TLS_KEYSTORE_PASSWORD)
                .keystoreType("PKCS12"));
        httpsWireMockServer.start();
        httpsBaseUrl = "https://localhost:" + httpsWireMockServer.httpsPort();
    }

    @AfterAll
    static void stopWireMock() {
        wireMockServer.stop();
        crossOriginWireMockServer.stop();
        httpsWireMockServer.stop();
    }

    @BeforeEach
    void resetStubs() {
        wireMockServer.resetAll();
        crossOriginWireMockServer.resetAll();
        httpsWireMockServer.resetAll();
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

    // ---- no-challenge path still works with an auth-aware source -------------------------------

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

    // ---- redirected authentication dance (ADR-0029) -------------------------------------------
    // The raw probe, token request, and authenticated tags request all share redirect policy.

    @Test
    void endToEndDance_followsRedirectsOnAllThreeHttpLegs_andReturnsTheSelectedVersion() {
        String username = "user";
        String password = "s3cr3t";
        String expectedBasic = expectedBasicAuth(username, password);
        String challengePath = TAGS_PATH + "-challenge";
        String tokenFinalPath = TOKEN_PATH + "-final";
        String tagsFinalPath = TAGS_PATH + "-authenticated";

        // Leg 1: raw probe redirected; only the redirected path serves the 401 challenge.
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .willReturn(aResponse().withStatus(301).withHeader("Location", challengePath)));
        String wwwAuthenticate = "Bearer realm=\"" + BASE_URL + TOKEN_PATH
                + "\",service=\"" + REGISTRY_SERVICE + "\",scope=\"" + CHALLENGE_SCOPE + "\"";
        wireMockServer.stubFor(get(urlPathEqualTo(challengePath))
                .willReturn(aResponse().withStatus(401).withHeader("WWW-Authenticate", wwwAuthenticate)));

        // Leg 2: token mint redirected; only the redirected path mints a token, and only when the
        // final request carries Basic auth plus the verbatim service/scope query.
        String tokenLocation = tokenFinalPath + "?service=" + REGISTRY_SERVICE
                + "&scope=" + URLEncoder.encode(CHALLENGE_SCOPE, StandardCharsets.UTF_8);
        wireMockServer.stubFor(get(urlPathEqualTo(TOKEN_PATH))
                .willReturn(aResponse().withStatus(301).withHeader("Location", tokenLocation)));
        wireMockServer.stubFor(get(urlPathEqualTo(tokenFinalPath))
                .withHeader("Authorization", equalTo(expectedBasic))
                .withQueryParam("service", equalTo(REGISTRY_SERVICE))
                .withQueryParam("scope", equalTo(CHALLENGE_SCOPE))
                .willReturn(jsonResponse(200, """
                        {"token": "%s"}
                        """.formatted(MINTED_TOKEN))));

        // Leg 3: authenticated tags redirected; only the redirected path serves the tags list, and
        // only when the final request carries the minted Bearer token.
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .withHeader("Authorization", equalTo("Bearer " + MINTED_TOKEN))
                .willReturn(aResponse().withStatus(301).withHeader("Location", tagsFinalPath)));
        wireMockServer.stubFor(get(urlPathEqualTo(tagsFinalPath))
                .withHeader("Authorization", equalTo("Bearer " + MINTED_TOKEN))
                .willReturn(jsonResponse(200, tagsListBody("1.25.3", "1.24.0", "latest"))));

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.of(username), Optional.of(password),
                DEFAULT_SELECTION, SEMVER_PARSER);

        VersionValue result = latestSource.version();

        assertEquals("1.25.3", result.value(),
                "the challenge -> mint -> authenticated-tags dance must compose across three "
                        + "separately redirected HTTP legs and still select the largest clean semver");
        wireMockServer.verify(getRequestedFor(urlPathEqualTo(tokenFinalPath))
                .withHeader("Authorization", equalTo(expectedBasic))
                .withQueryParam("service", equalTo(REGISTRY_SERVICE))
                .withQueryParam("scope", equalTo(CHALLENGE_SCOPE)));
        wireMockServer.verify(getRequestedFor(urlPathEqualTo(tagsFinalPath))
                .withHeader("Authorization", equalTo("Bearer " + MINTED_TOKEN)));
    }

    @Test
    void sameOriginTokenRedirect_finalRequest_carriesExpectedBasicAuthAndVerbatimServiceScope() {
        stubChallenge401();
        String tokenFinalPath = TOKEN_PATH + "-final";
        String location = tokenFinalPath + "?service=" + REGISTRY_SERVICE
                + "&scope=" + URLEncoder.encode(CHALLENGE_SCOPE, StandardCharsets.UTF_8);
        wireMockServer.stubFor(get(urlPathEqualTo(TOKEN_PATH))
                .willReturn(aResponse().withStatus(302).withHeader("Location", location)));
        String expectedBasic = expectedBasicAuth("user", "s3cr3t");
        wireMockServer.stubFor(get(urlPathEqualTo(tokenFinalPath))
                .withHeader("Authorization", equalTo(expectedBasic))
                .withQueryParam("service", equalTo(REGISTRY_SERVICE))
                .withQueryParam("scope", equalTo(CHALLENGE_SCOPE))
                .willReturn(jsonResponse(200, """
                        {"token": "%s"}
                        """.formatted(MINTED_TOKEN))));
        stubTagsListSuccess();

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.of("user"), Optional.of("s3cr3t"),
                DEFAULT_SELECTION, SEMVER_PARSER);

        latestSource.version();

        wireMockServer.verify(getRequestedFor(urlPathEqualTo(tokenFinalPath))
                .withHeader("Authorization", equalTo(expectedBasic))
                .withQueryParam("service", equalTo(REGISTRY_SERVICE))
                .withQueryParam("scope", equalTo(CHALLENGE_SCOPE)));
    }

    @Test
    void sameOriginAuthenticatedTagsRedirect_finalRequest_carriesBearerToken_notBasicCredential() {
        stubChallenge401();
        stubTokenEndpointWithBasicAuth("user", "s3cr3t");
        String tagsFinalPath = TAGS_PATH + "-final";
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .withHeader("Authorization", equalTo("Bearer " + MINTED_TOKEN))
                .willReturn(aResponse().withStatus(302).withHeader("Location", tagsFinalPath)));
        wireMockServer.stubFor(get(urlPathEqualTo(tagsFinalPath))
                .withHeader("Authorization", equalTo("Bearer " + MINTED_TOKEN))
                .willReturn(jsonResponse(200, tagsListBody("1.9.0"))));

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.of("user"), Optional.of("s3cr3t"),
                DEFAULT_SELECTION, SEMVER_PARSER);

        VersionValue result = latestSource.version();

        assertEquals("1.9.0", result.value());
        wireMockServer.verify(getRequestedFor(urlPathEqualTo(tagsFinalPath))
                .withHeader("Authorization", equalTo("Bearer " + MINTED_TOKEN)));
    }

    @Test
    void crossOriginTokenRedirect_targetReceivesNoBasicAuthorization_butIsStillContacted() {
        stubChallenge401();
        String crossOriginPath = "/token-cross-origin";
        String crossOriginLocation = "http://localhost:" + CROSS_ORIGIN_PORT + crossOriginPath
                + "?service=" + REGISTRY_SERVICE
                + "&scope=" + URLEncoder.encode(CHALLENGE_SCOPE, StandardCharsets.UTF_8);
        wireMockServer.stubFor(get(urlPathEqualTo(TOKEN_PATH))
                .willReturn(aResponse().withStatus(302).withHeader("Location", crossOriginLocation)));
        crossOriginWireMockServer.stubFor(get(urlPathEqualTo(crossOriginPath))
                .willReturn(jsonResponse(200, """
                        {"token": "%s"}
                        """.formatted(MINTED_TOKEN))));
        stubTagsListSuccess();

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.of("user"), Optional.of("s3cr3t"),
                DEFAULT_SELECTION, SEMVER_PARSER);

        VersionValue result = latestSource.version();

        assertEquals("1.25.3", result.value(),
                "the cross-origin token redirect must still be followed to a working target");
        crossOriginWireMockServer.verify(getRequestedFor(urlPathEqualTo(crossOriginPath))
                .withHeader("Authorization", absent()));
    }

    @Test
    void crossOriginAuthenticatedTagsRedirect_targetReceivesNoBearerAuthorization() {
        stubChallenge401();
        stubTokenEndpointWithBasicAuth("user", "s3cr3t");
        String crossOriginPath = "/tags-cross-origin";
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .withHeader("Authorization", equalTo("Bearer " + MINTED_TOKEN))
                .willReturn(aResponse().withStatus(302)
                        .withHeader("Location", "http://localhost:" + CROSS_ORIGIN_PORT + crossOriginPath)));
        crossOriginWireMockServer.stubFor(get(urlPathEqualTo(crossOriginPath))
                .willReturn(jsonResponse(200, tagsListBody("2.2.2"))));

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.of("user"), Optional.of("s3cr3t"),
                DEFAULT_SELECTION, SEMVER_PARSER);

        VersionValue result = latestSource.version();

        assertEquals("2.2.2", result.value(),
                "the cross-origin authenticated-tags redirect must still be followed to a working target");
        crossOriginWireMockServer.verify(getRequestedFor(urlPathEqualTo(crossOriginPath))
                .withHeader("Authorization", absent()));
    }

    @Test
    void httpsToHttpDowngrade_onTokenLeg_refusedBeforeContactingTheHttpTarget() {
        // Raw probe + challenge stay on the primary plain-HTTP server (succeed normally); only the
        // challenge's realm points at the untrusted HTTPS server, so this isolates the token leg.
        String realmPath = "/token-https";
        String wwwAuthenticate = "Bearer realm=\"" + httpsBaseUrl + realmPath
                + "\",service=\"" + REGISTRY_SERVICE + "\",scope=\"" + CHALLENGE_SCOPE + "\"";
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .willReturn(aResponse().withStatus(401).withHeader("WWW-Authenticate", wwwAuthenticate)));
        httpsWireMockServer.stubFor(get(urlPathEqualTo(realmPath))
                .willReturn(aResponse().withStatus(301)
                        .withHeader("Location", "http://localhost:" + PORT + "/downgraded-token")));

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.of("user"), Optional.of("s3cr3t"),
                DEFAULT_SELECTION, SEMVER_PARSER);

        assertThrows(RuntimeException.class, latestSource::version,
                "an HTTPS token realm redirecting to plain HTTP must never resolve to a version");
        wireMockServer.verify(0, getRequestedFor(urlPathEqualTo("/downgraded-token")));
    }

    @Test
    void httpsToHttpDowngrade_onAuthenticatedTagsLeg_refusedBeforeContactingTheHttpTarget() {
        // The whole registry is HTTPS here so the authenticated-tags client's baseUri (which shares
        // the source's single baseUrl with the raw probe) is HTTPS too. The self-signed cert is
        // deliberately untrusted (OciRegistryLatestSource has no truststore knob), so this may fail
        // as early as the raw probe's own TLS handshake rather than at an explicit redirect — see the
        // class-level comment on httpsWireMockServer. Either way the plain-HTTP downgrade target must
        // never be contacted.
        httpsWireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .willReturn(aResponse().withStatus(301)
                        .withHeader("Location", "http://localhost:" + PORT + "/downgraded-tags")));

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                httpsBaseUrl + "/v2/" + REPO, Optional.of("user"), Optional.of("s3cr3t"),
                DEFAULT_SELECTION, SEMVER_PARSER);

        assertThrows(RuntimeException.class, latestSource::version,
                "an HTTPS registry redirecting the authenticated-tags leg to plain HTTP must never "
                        + "resolve to a version");
        wireMockServer.verify(0, getRequestedFor(urlPathEqualTo("/downgraded-tags")));
    }

    // ---- authenticated-leg non-2xx and authenticated pagination ------------------------------
    // These two tests pin down APPROVED behavior of the diagnostic guard added alongside the
    // redirect-following work above: a FINAL non-2xx response on the tags-page fetch must fail
    // closed (IllegalStateException, a RuntimeException) rather than being silently mis-parsed —
    // including on the AUTHENTICATED (post-mint, Bearer-carrying) leg — and the authenticated leg
    // must thread Link-header pagination the same way the anonymous leg does (see
    // OciRegistryLatestSourcePaginationIT for the anonymous multi-page style this mirrors).

    @Test
    void authenticatedTagsRequest_nonSuccessStatus_failsClosed_throwsRuntimeException() {
        stubChallenge401();
        stubTokenEndpoint();
        // The unauthenticated raw probe still gets the 401 challenge (via stubChallenge401 above,
        // which matches any request to TAGS_PATH regardless of headers). The Bearer-carrying retry
        // must be matched by a MORE SPECIFIC stub (WireMock prefers the more specific match), which
        // returns a non-2xx here instead of a tags list.
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .withHeader("Authorization", equalTo("Bearer " + MINTED_TOKEN))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("internal registry error")));

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.empty(), Optional.empty(), DEFAULT_SELECTION, SEMVER_PARSER);

        assertThrows(RuntimeException.class, latestSource::version,
                "a token-minted-but-tags-fail scenario (non-2xx on the authenticated leg) must fail "
                        + "closed rather than silently mis-parsing the response body");
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

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.empty(), Optional.empty(),
                new TagSelection(2, 100, Optional.empty(), false), SEMVER_PARSER);

        VersionValue result = latestSource.version();

        assertEquals("2.0.0", result.value(),
                "the authenticated (Bearer) leg must thread Link-header pagination across pages just "
                        + "like the anonymous leg, so the largest tag on page 2 is selected");
        wireMockServer.verify(getRequestedFor(urlPathEqualTo(TAGS_PATH))
                .withHeader("Authorization", equalTo("Bearer " + MINTED_TOKEN))
                .withQueryParam("last", equalTo("1.1.0")));
    }

    private static String expectedBasicAuth(String username, String password) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
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
