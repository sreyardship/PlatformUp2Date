package org.yardship.integration.adapters.out.versionsource.latest.ociregistry;

import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.yardship.adapters.out.versionsource.latest.ociregistry.OciRegistryLatestSource;
import org.yardship.adapters.out.versionsource.latest.ociregistry.TagSelection;
import org.yardship.core.domain.primitives.VersionValue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.stream.Stream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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


    private static final String KEYSTORE_RESOURCE = "tls/wiremock-localhost.p12";
    private static final String KEYSTORE_PASSWORD = "password";

    static final int PORT = 8091;
    static final int CROSS_ORIGIN_PORT = 8093;
    static final String BASE_URL = "http://localhost:" + PORT;
    static final String CROSS_ORIGIN_BASE_URL = "http://localhost:" + CROSS_ORIGIN_PORT;
    static final String REGISTRY_SERVICE = "registry.example.com";
    static final String REPO = "library/nginx";
    static final String TAGS_PATH = "/v2/" + REPO + "/tags/list";
    static final String TOKEN_PATH = "/token";
    static final String MINTED_TOKEN = "minted-bearer-xyz";
    static final String CHALLENGE_SCOPE = "repository:library/nginx:pull";

    static WireMockServer wireMockServer;
    static WireMockServer crossOriginWireMockServer;
    static WireMockServer httpsWireMockServer;

    private static final TagSelection DEFAULT_SELECTION =
            new TagSelection(100, 1000, Optional.empty(), false);

    @BeforeAll
    static void startWireMock() throws Exception {
        wireMockServer = new WireMockServer(options().port(PORT));
        wireMockServer.start();
        crossOriginWireMockServer = new WireMockServer(options().port(CROSS_ORIGIN_PORT));
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
        wireMockServer.stop();
        crossOriginWireMockServer.stop();
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
        wireMockServer.stubFor(get(urlPathEqualTo(TOKEN_PATH))
                .withQueryParam("service", equalTo(REGISTRY_SERVICE))
                .withQueryParam("scope", equalTo(CHALLENGE_SCOPE))
                .withHeader("Authorization", absent())
                .willReturn(jsonResponse(200, "{\"token\": \"" + MINTED_TOKEN + "\"}")));
        stubTagsListSuccess();

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.empty(), Optional.empty(), DEFAULT_SELECTION, SEMVER_PARSER);

        VersionValue result = latestSource.version();

        assertEquals("1.25.3", result.value(),
                "After the bearer dance the source must return the largest clean semver from tags/list");
        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo(TOKEN_PATH))
                .withQueryParam("service", equalTo(REGISTRY_SERVICE))
                .withQueryParam("scope", equalTo(CHALLENGE_SCOPE))
                .withHeader("Authorization", absent()));
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
    void redirectedRawProbe_final401_remainsAvailableToChallengeParsing() {
        String initialTags = TAGS_PATH + "?n=100";
        String redirectedChallenge = "/redirected/challenge?probe=1";
        String challenge = "Bearer realm=\"" + BASE_URL + TOKEN_PATH
                + "\",service=\"" + REGISTRY_SERVICE
                + "\",scope=\"" + CHALLENGE_SCOPE + "\"";

        wireMockServer.stubFor(get(urlEqualTo(initialTags))
                .willReturn(aResponse()
                        .withStatus(302)
                        .withHeader("Location", redirectedChallenge)));
        wireMockServer.stubFor(get(urlEqualTo(redirectedChallenge))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("WWW-Authenticate", challenge)));
        wireMockServer.stubFor(get(urlPathEqualTo(TOKEN_PATH))
                .withQueryParam("service", equalTo(REGISTRY_SERVICE))
                .withQueryParam("scope", equalTo(CHALLENGE_SCOPE))
                .willReturn(jsonResponse(200, "{\"token\": \"" + MINTED_TOKEN + "\"}")));
        wireMockServer.stubFor(get(urlEqualTo(initialTags))
                .withHeader("Authorization", equalTo("Bearer " + MINTED_TOKEN))
                .willReturn(jsonResponse(200, tagsListBody("1.25.3"))));

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.empty(), Optional.empty(), DEFAULT_SELECTION, SEMVER_PARSER);

        VersionValue result = latestSource.version();

        assertEquals("1.25.3", result.value(),
                "a redirected raw probe must expose its final 401 challenge to the bearer dance");
        wireMockServer.verify(1, getRequestedFor(urlEqualTo(redirectedChallenge)));
        wireMockServer.verify(1, getRequestedFor(urlPathEqualTo(TOKEN_PATH))
                .withQueryParam("service", equalTo(REGISTRY_SERVICE))
                .withQueryParam("scope", equalTo(CHALLENGE_SCOPE)));
    }

    @ParameterizedTest(name = "raw probe redirect status {0} preserves challenge")
    @MethodSource("supportedRedirectStatuses")
    void rawProbe_supportedGetRedirectStatuses_preserveFinal401Challenge(int redirectStatus) {
        String initialTags = TAGS_PATH + "?n=100";
        String redirectedChallenge = "/redirected/raw-challenge-" + redirectStatus + "?probe=1";
        String challenge = "Bearer realm=\"" + BASE_URL + TOKEN_PATH
                + "\",service=\"" + REGISTRY_SERVICE
                + "\",scope=\"" + CHALLENGE_SCOPE + "\"";

        wireMockServer.stubFor(get(urlEqualTo(initialTags))
                .willReturn(aResponse()
                        .withStatus(redirectStatus)
                        .withHeader("Location", redirectedChallenge)));
        wireMockServer.stubFor(get(urlEqualTo(redirectedChallenge))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("WWW-Authenticate", challenge)));
        wireMockServer.stubFor(get(urlPathEqualTo(TOKEN_PATH))
                .willReturn(jsonResponse(200, "{\"token\": \"" + MINTED_TOKEN + "\"}")));
        wireMockServer.stubFor(get(urlEqualTo(initialTags))
                .withHeader("Authorization", equalTo("Bearer " + MINTED_TOKEN))
                .willReturn(jsonResponse(200, tagsListBody("1.25.3"))));

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.empty(), Optional.empty(), DEFAULT_SELECTION, SEMVER_PARSER);

        VersionValue result = latestSource.version();

        assertEquals("1.25.3", result.value(),
                "the final 401 from every supported raw-probe redirect must reach challenge parsing");
        wireMockServer.verify(getRequestedFor(urlEqualTo(redirectedChallenge)));
        wireMockServer.verify(getRequestedFor(urlPathEqualTo(TOKEN_PATH))
                .withQueryParam("service", equalTo(REGISTRY_SERVICE))
                .withQueryParam("scope", equalTo(CHALLENGE_SCOPE)));
    }

    @Test
    void sameOriginTokenRedirect_receivesBasicAuthorizationAndChallengeQueryVerbatim() {
        String challengeService = "delegated.registry.example";
        String challengeScope = "repository:another/image:pull";
        String tokenRedirect = "/redirected/token-start";
        String tokenFinal = "/redirected/token-final?service=" + challengeService
                + "&scope=" + challengeScope;
        String tokenRequest = tokenRedirect + "?service=" + challengeService
                + "&scope=" + challengeScope;
        String expectedBasic = "Basic " + Base64.getEncoder()
                .encodeToString("user:s3cr3t".getBytes(StandardCharsets.UTF_8));
        String challenge = "Bearer realm=\"" + BASE_URL + tokenRedirect
                + "\",service=\"" + challengeService
                + "\",scope=\"" + challengeScope + "\"";

        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("WWW-Authenticate", challenge)));
        wireMockServer.stubFor(get(urlEqualTo(tokenRequest))
                .withHeader("Authorization", equalTo(expectedBasic))
                .willReturn(aResponse()
                        .withStatus(307)
                        .withHeader("Location", tokenFinal)));
        wireMockServer.stubFor(get(urlEqualTo(tokenFinal))
                .withHeader("Authorization", equalTo(expectedBasic))
                .willReturn(jsonResponse(200, "{\"token\": \"" + MINTED_TOKEN + "\"}")));
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .withHeader("Authorization", equalTo("Bearer " + MINTED_TOKEN))
                .willReturn(jsonResponse(200, tagsListBody("1.25.3"))));

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.of("user"), Optional.of("s3cr3t"),
                DEFAULT_SELECTION, SEMVER_PARSER);

        latestSource.version();

        wireMockServer.verify(1, getRequestedFor(urlEqualTo(tokenRequest))
                .withHeader("Authorization", equalTo(expectedBasic))
                .withQueryParam("service", equalTo(challengeService))
                .withQueryParam("scope", equalTo(challengeScope)));
        wireMockServer.verify(1, getRequestedFor(urlEqualTo(tokenFinal))
                .withHeader("Authorization", equalTo(expectedBasic))
                .withQueryParam("service", equalTo(challengeService))
                .withQueryParam("scope", equalTo(challengeScope)));
    }

    @Test
    void crossOriginTokenRedirect_doesNotForwardBasicAuthorization_toDifferentHostOnSamePort() {
        String tokenRedirect = "/redirected/token-start-different-host";
        String crossOriginToken = "/redirected/token-final-different-host?service=" + REGISTRY_SERVICE
                + "&scope=" + CHALLENGE_SCOPE;
        String differentHostBaseUrl = "http://127.0.0.1:" + PORT;
        String expectedBasic = "Basic " + Base64.getEncoder()
                .encodeToString("user:s3cr3t".getBytes(StandardCharsets.UTF_8));
        String challenge = "Bearer realm=\"" + BASE_URL + tokenRedirect
                + "\",service=\"" + REGISTRY_SERVICE
                + "\",scope=\"" + CHALLENGE_SCOPE + "\"";

        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("WWW-Authenticate", challenge)));
        wireMockServer.stubFor(get(urlEqualTo(tokenRedirect + "?service=" + REGISTRY_SERVICE
                + "&scope=" + CHALLENGE_SCOPE))
                .withHeader("Authorization", equalTo(expectedBasic))
                .willReturn(aResponse()
                        .withStatus(302)
                        .withHeader("Location", differentHostBaseUrl + crossOriginToken)));
        wireMockServer.stubFor(get(urlEqualTo(crossOriginToken))
                .withHeader("Authorization", absent())
                .willReturn(jsonResponse(200, "{\"token\": \"" + MINTED_TOKEN + "\"}")));
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .withHeader("Authorization", equalTo("Bearer " + MINTED_TOKEN))
                .willReturn(jsonResponse(200, tagsListBody("1.25.3"))));

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.of("user"), Optional.of("s3cr3t"),
                DEFAULT_SELECTION, SEMVER_PARSER);

        VersionValue result = latestSource.version();

        assertEquals("1.25.3", result.value());
        wireMockServer.verify(getRequestedFor(urlEqualTo(crossOriginToken))
                .withHeader("Authorization", absent()));
    }

    @ParameterizedTest(name = "same-origin token redirect status {0}")
    @MethodSource("sameOriginTokenRedirectStatuses")
    void sameOriginTokenRedirect_supportedGetStatuses_retainBasicAuthorization(int redirectStatus) {
        String tokenRedirect = "/redirected/token-start-" + redirectStatus;
        String tokenFinal = "/redirected/token-final-" + redirectStatus
                + "?service=" + REGISTRY_SERVICE + "&scope=" + CHALLENGE_SCOPE;
        String expectedBasic = "Basic " + Base64.getEncoder()
                .encodeToString("user:s3cr3t".getBytes(StandardCharsets.UTF_8));
        String challenge = "Bearer realm=\"" + BASE_URL + tokenRedirect
                + "\",service=\"" + REGISTRY_SERVICE
                + "\",scope=\"" + CHALLENGE_SCOPE + "\"";

        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("WWW-Authenticate", challenge)));
        wireMockServer.stubFor(get(urlEqualTo(tokenRedirect + "?service=" + REGISTRY_SERVICE
                + "&scope=" + CHALLENGE_SCOPE))
                .withHeader("Authorization", equalTo(expectedBasic))
                .willReturn(aResponse()
                        .withStatus(redirectStatus)
                        .withHeader("Location", tokenFinal)));
        wireMockServer.stubFor(get(urlEqualTo(tokenFinal))
                .withHeader("Authorization", equalTo(expectedBasic))
                .willReturn(jsonResponse(200, "{\"token\": \"" + MINTED_TOKEN + "\"}")));
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .withHeader("Authorization", equalTo("Bearer " + MINTED_TOKEN))
                .willReturn(jsonResponse(200, tagsListBody("1.25.3"))));

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.of("user"), Optional.of("s3cr3t"),
                DEFAULT_SELECTION, SEMVER_PARSER);

        VersionValue result = latestSource.version();

        assertEquals("1.25.3", result.value());
        wireMockServer.verify(getRequestedFor(urlEqualTo(tokenFinal))
                .withHeader("Authorization", equalTo(expectedBasic)));
    }

    @Test
    void crossOriginTokenRedirect_doesNotForwardBasicAuthorization() {
        String tokenRedirect = "/redirected/token-start";
        String crossOriginToken = "/redirected/token-final?service=" + REGISTRY_SERVICE
                + "&scope=" + CHALLENGE_SCOPE;
        String expectedBasic = "Basic " + Base64.getEncoder()
                .encodeToString("user:s3cr3t".getBytes(StandardCharsets.UTF_8));
        String challenge = "Bearer realm=\"" + BASE_URL + tokenRedirect
                + "\",service=\"" + REGISTRY_SERVICE
                + "\",scope=\"" + CHALLENGE_SCOPE + "\"";

        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("WWW-Authenticate", challenge)));
        wireMockServer.stubFor(get(urlEqualTo(tokenRedirect + "?service=" + REGISTRY_SERVICE
                + "&scope=" + CHALLENGE_SCOPE))
                .withHeader("Authorization", equalTo(expectedBasic))
                .willReturn(aResponse()
                        .withStatus(302)
                        .withHeader("Location", CROSS_ORIGIN_BASE_URL + crossOriginToken)));
        crossOriginWireMockServer.stubFor(get(urlEqualTo(crossOriginToken))
                .withHeader("Authorization", absent())
                .willReturn(jsonResponse(200, "{\"token\": \"" + MINTED_TOKEN + "\"}")));
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .withHeader("Authorization", equalTo("Bearer " + MINTED_TOKEN))
                .willReturn(jsonResponse(200, tagsListBody("1.25.3"))));

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.of("user"), Optional.of("s3cr3t"),
                DEFAULT_SELECTION, SEMVER_PARSER);

        VersionValue result = latestSource.version();

        assertEquals("1.25.3", result.value());
        crossOriginWireMockServer.verify(getRequestedFor(urlEqualTo(crossOriginToken))
                .withHeader("Authorization", absent()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("httpsToHttpCredentialLegs")
    void httpsToHttpCredentialRedirect_isRefusedBeforeTargetContact(String credentialLeg) throws Exception {
        String targetPath = "/redirected/https-to-http-" + credentialLeg + "-target";
        String expectedBasic = "Basic " + Base64.getEncoder()
                .encodeToString("user:s3cr3t".getBytes(StandardCharsets.UTF_8));
        SSLContext previous = SSLContext.getDefault();
        String previousTrustStore = System.getProperty("javax.net.ssl.trustStore");
        String previousTrustStorePassword = System.getProperty("javax.net.ssl.trustStorePassword");
        String previousTrustStoreType = System.getProperty("javax.net.ssl.trustStoreType");
        try {
            SSLContext.setDefault(trustedWireMockContext());
            System.setProperty("javax.net.ssl.trustStore", resourcePath(KEYSTORE_RESOURCE));
            System.setProperty("javax.net.ssl.trustStorePassword", KEYSTORE_PASSWORD);
            System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");
            OciRegistryLatestSource latestSource;
            if ("token".equals(credentialLeg)) {
                String tokenStart = "/redirected/https-token-start";
                String challenge = "Bearer realm=\"https://localhost:" + httpsWireMockServer.httpsPort()
                        + tokenStart + "\",service=\"" + REGISTRY_SERVICE
                        + "\",scope=\"" + CHALLENGE_SCOPE + "\"";
                wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                        .willReturn(aResponse()
                                .withStatus(401)
                                .withHeader("WWW-Authenticate", challenge)));
                httpsWireMockServer.stubFor(get(urlPathEqualTo(tokenStart))
                        .withHeader("Authorization", equalTo(expectedBasic))
                        .willReturn(aResponse()
                                .withStatus(302)
                                .withHeader("Location", BASE_URL + targetPath)));
                latestSource = new OciRegistryLatestSource(
                        BASE_URL + "/v2/" + REPO, Optional.of("user"), Optional.of("s3cr3t"),
                        DEFAULT_SELECTION, SEMVER_PARSER);
            } else {
                String authenticatedTags = TAGS_PATH + "?n=100";
                String challenge = "Bearer realm=\"https://localhost:" + httpsWireMockServer.httpsPort()
                        + TOKEN_PATH + "\",service=\"" + REGISTRY_SERVICE
                        + "\",scope=\"" + CHALLENGE_SCOPE + "\"";
                httpsWireMockServer.stubFor(get(urlEqualTo(authenticatedTags))
                        .willReturn(aResponse()
                                .withStatus(401)
                                .withHeader("WWW-Authenticate", challenge)));
                httpsWireMockServer.stubFor(get(urlPathEqualTo(TOKEN_PATH))
                        .withHeader("Authorization", equalTo(expectedBasic))
                        .willReturn(jsonResponse(200, "{\"token\": \"" + MINTED_TOKEN + "\"}")));
                httpsWireMockServer.stubFor(get(urlEqualTo(authenticatedTags))
                        .withHeader("Authorization", equalTo("Bearer " + MINTED_TOKEN))
                        .willReturn(aResponse()
                                .withStatus(302)
                                .withHeader("Location", BASE_URL + targetPath)));
                latestSource = new OciRegistryLatestSource(
                        "https://localhost:" + httpsWireMockServer.httpsPort() + "/v2/" + REPO,
                        Optional.of("user"), Optional.of("s3cr3t"), DEFAULT_SELECTION, SEMVER_PARSER);
            }

            IllegalStateException failure = assertThrows(IllegalStateException.class, latestSource::version,
                    "an HTTPS-to-HTTP redirect on the " + credentialLeg
                            + " credential-bearing leg must be refused");
            org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().contains("HTTPS-to-HTTP"),
                    "the refusal must identify the rejected scheme downgrade");
        } finally {
            SSLContext.setDefault(previous);
            restoreProperty("javax.net.ssl.trustStore", previousTrustStore);
            restoreProperty("javax.net.ssl.trustStorePassword", previousTrustStorePassword);
            restoreProperty("javax.net.ssl.trustStoreType", previousTrustStoreType);
        }

        if ("token".equals(credentialLeg)) {
            httpsWireMockServer.verify(getRequestedFor(urlPathEqualTo("/redirected/https-token-start"))
                    .withHeader("Authorization", equalTo(expectedBasic)));
        } else {
            httpsWireMockServer.verify(getRequestedFor(urlEqualTo(TAGS_PATH + "?n=100"))
                    .withHeader("Authorization", equalTo("Bearer " + MINTED_TOKEN)));
        }
        wireMockServer.verify(0, getRequestedFor(urlEqualTo(targetPath)));
    }

    @Test
    void redirectedAuthenticatedDance_followsChallengeMintAndTagsRedirects_andSelectsVersion() {
        String initialTags = TAGS_PATH + "?n=100";
        String redirectedChallenge = "/redirected/challenge?probe=1";
        String tokenRedirect = "/redirected/token-start";
        String tokenFinal = "/redirected/token-final?service=" + REGISTRY_SERVICE
                + "&scope=" + CHALLENGE_SCOPE;
        String authenticatedTagsFinal = "/redirected/authenticated-tags?n=100";
        String expectedBasic = "Basic " + Base64.getEncoder()
                .encodeToString("user:s3cr3t".getBytes(StandardCharsets.UTF_8));
        String challenge = "Bearer realm=\"" + BASE_URL + tokenRedirect
                + "\",service=\"" + REGISTRY_SERVICE
                + "\",scope=\"" + CHALLENGE_SCOPE + "\"";

        wireMockServer.stubFor(get(urlEqualTo(initialTags))
                .willReturn(aResponse()
                        .withStatus(302)
                        .withHeader("Location", redirectedChallenge)));
        wireMockServer.stubFor(get(urlEqualTo(redirectedChallenge))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("WWW-Authenticate", challenge)));
        wireMockServer.stubFor(get(urlEqualTo(tokenRedirect + "?service=" + REGISTRY_SERVICE
                + "&scope=" + CHALLENGE_SCOPE))
                .withHeader("Authorization", equalTo(expectedBasic))
                .willReturn(aResponse()
                        .withStatus(307)
                        .withHeader("Location", tokenFinal)));
        wireMockServer.stubFor(get(urlEqualTo(tokenFinal))
                .withHeader("Authorization", equalTo(expectedBasic))
                .willReturn(jsonResponse(200, "{\"token\": \"" + MINTED_TOKEN + "\"}")));
        wireMockServer.stubFor(get(urlEqualTo(initialTags))
                .withHeader("Authorization", equalTo("Bearer " + MINTED_TOKEN))
                .willReturn(aResponse()
                        .withStatus(302)
                        .withHeader("Location", authenticatedTagsFinal)));
        wireMockServer.stubFor(get(urlEqualTo(authenticatedTagsFinal))
                .withHeader("Authorization", equalTo("Bearer " + MINTED_TOKEN))
                .willReturn(jsonResponse(200, tagsListBody("1.25.3", "1.24.0", "latest"))));

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.of("user"), Optional.of("s3cr3t"),
                DEFAULT_SELECTION, SEMVER_PARSER);

        VersionValue result = latestSource.version();

        assertEquals("1.25.3", result.value(),
                "the redirected challenge, token mint, and authenticated tags requests must compose");

        // The result alone is not enough to prove that each separately-built client traversed its
        // own redirect. Verify every hop: the raw probe, both token-client hops, and both
        // authenticated-tags hops all belong to this one redirect contract.
        wireMockServer.verify(1, getRequestedFor(urlEqualTo(initialTags))
                .withHeader("Authorization", absent()));
        wireMockServer.verify(1, getRequestedFor(urlEqualTo(redirectedChallenge))
                .withHeader("Authorization", absent()));
        wireMockServer.verify(1, getRequestedFor(urlEqualTo(tokenRedirect + "?service=" + REGISTRY_SERVICE
                + "&scope=" + CHALLENGE_SCOPE))
                .withHeader("Authorization", equalTo(expectedBasic)));
        wireMockServer.verify(1, getRequestedFor(urlEqualTo(tokenFinal))
                .withHeader("Authorization", equalTo(expectedBasic)));
        wireMockServer.verify(1, getRequestedFor(urlEqualTo(initialTags))
                .withHeader("Authorization", equalTo("Bearer " + MINTED_TOKEN)));
        wireMockServer.verify(1, getRequestedFor(urlEqualTo(authenticatedTagsFinal))
                .withHeader("Authorization", equalTo("Bearer " + MINTED_TOKEN)));
    }

    @Test
    void crossOriginTokenRedirect_doesNotRestoreBasicAuthorization_whenChainReturnsToRegistryOrigin() {
        String tokenRedirect = "/redirected/token-start";
        String crossOriginToken = "/redirected/token-cross-origin?service=" + REGISTRY_SERVICE
                + "&scope=" + CHALLENGE_SCOPE;
        String returnedToken = "/redirected/token-returned?service=" + REGISTRY_SERVICE
                + "&scope=" + CHALLENGE_SCOPE;
        String expectedBasic = "Basic " + Base64.getEncoder()
                .encodeToString("user:s3cr3t".getBytes(StandardCharsets.UTF_8));
        String challenge = "Bearer realm=\"" + BASE_URL + tokenRedirect
                + "\",service=\"" + REGISTRY_SERVICE
                + "\",scope=\"" + CHALLENGE_SCOPE + "\"";

        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("WWW-Authenticate", challenge)));
        wireMockServer.stubFor(get(urlEqualTo(tokenRedirect + "?service=" + REGISTRY_SERVICE
                + "&scope=" + CHALLENGE_SCOPE))
                .withHeader("Authorization", equalTo(expectedBasic))
                .willReturn(aResponse()
                        .withStatus(302)
                        .withHeader("Location", CROSS_ORIGIN_BASE_URL + crossOriginToken)));
        crossOriginWireMockServer.stubFor(get(urlEqualTo(crossOriginToken))
                .withHeader("Authorization", absent())
                .willReturn(aResponse()
                        .withStatus(302)
                        .withHeader("Location", BASE_URL + returnedToken)));
        wireMockServer.stubFor(get(urlEqualTo(returnedToken))
                .willReturn(jsonResponse(200, "{\"token\": \"" + MINTED_TOKEN + "\"}")));
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .withHeader("Authorization", equalTo("Bearer " + MINTED_TOKEN))
                .willReturn(jsonResponse(200, tagsListBody("1.25.3"))));

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.of("user"), Optional.of("s3cr3t"),
                DEFAULT_SELECTION, SEMVER_PARSER);

        VersionValue result = latestSource.version();

        assertEquals("1.25.3", result.value());
        wireMockServer.verify(getRequestedFor(urlEqualTo(tokenRedirect + "?service=" + REGISTRY_SERVICE
                + "&scope=" + CHALLENGE_SCOPE))
                .withHeader("Authorization", equalTo(expectedBasic)));
        crossOriginWireMockServer.verify(getRequestedFor(urlEqualTo(crossOriginToken))
                .withHeader("Authorization", absent()));
        wireMockServer.verify(getRequestedFor(urlEqualTo(returnedToken))
                .withHeader("Authorization", absent()));
    }

    @Test
    void sameOriginAuthenticatedTagsRedirect_receivesBearerAuthorization_withoutConfiguredBasicCredential() {
        String authenticatedTags = TAGS_PATH + "?n=100";
        String authenticatedTagsFinal = "/redirected/authenticated-tags-final?n=100";
        String expectedBasic = "Basic " + Base64.getEncoder()
                .encodeToString("user:s3cr3t".getBytes(StandardCharsets.UTF_8));

        stubChallenge401();
        stubTokenEndpointWithBasicAuth("user", "s3cr3t");
        wireMockServer.stubFor(get(urlEqualTo(authenticatedTags))
                .withHeader("Authorization", equalTo("Bearer " + MINTED_TOKEN))
                .willReturn(aResponse()
                        .withStatus(302)
                        .withHeader("Location", authenticatedTagsFinal)));
        wireMockServer.stubFor(get(urlEqualTo(authenticatedTagsFinal))
                .willReturn(jsonResponse(200, tagsListBody("1.25.3"))));

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.of("user"), Optional.of("s3cr3t"),
                DEFAULT_SELECTION, SEMVER_PARSER);

        latestSource.version();

        wireMockServer.verify(getRequestedFor(urlEqualTo(authenticatedTagsFinal))
                .withHeader("Authorization", equalTo("Bearer " + MINTED_TOKEN)));
        wireMockServer.verify(0, getRequestedFor(urlEqualTo(authenticatedTagsFinal))
                .withHeader("Authorization", equalTo(expectedBasic)));
    }

    @ParameterizedTest(name = "same-origin authenticated-tags redirect status {0}")
    @MethodSource("sameOriginAuthenticatedTagsRedirectStatuses")
    void sameOriginAuthenticatedTagsRedirect_supportedGetStatuses_retainBearerAuthorization(int redirectStatus) {
        String authenticatedTags = TAGS_PATH + "?n=100";
        String authenticatedTagsFinal = "/redirected/authenticated-tags-final-" + redirectStatus + "?n=100";
        String expectedBearer = "Bearer " + MINTED_TOKEN;
        String expectedBasic = "Basic " + Base64.getEncoder()
                .encodeToString("user:s3cr3t".getBytes(StandardCharsets.UTF_8));

        stubChallenge401();
        stubTokenEndpointWithBasicAuth("user", "s3cr3t");
        wireMockServer.stubFor(get(urlEqualTo(authenticatedTags))
                .withHeader("Authorization", equalTo(expectedBearer))
                .willReturn(aResponse()
                        .withStatus(redirectStatus)
                        .withHeader("Location", authenticatedTagsFinal)));
        wireMockServer.stubFor(get(urlEqualTo(authenticatedTagsFinal))
                .withHeader("Authorization", equalTo(expectedBearer))
                .willReturn(jsonResponse(200, tagsListBody("1.25.3"))));

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.of("user"), Optional.of("s3cr3t"),
                DEFAULT_SELECTION, SEMVER_PARSER);

        VersionValue result = latestSource.version();

        assertEquals("1.25.3", result.value());
        wireMockServer.verify(getRequestedFor(urlEqualTo(authenticatedTagsFinal))
                .withHeader("Authorization", equalTo(expectedBearer)));
        wireMockServer.verify(0, getRequestedFor(urlEqualTo(authenticatedTagsFinal))
                .withHeader("Authorization", equalTo(expectedBasic)));
    }

    @Test
    void crossOriginAuthenticatedTagsRedirect_doesNotForwardBearerAuthorization() {
        String authenticatedTags = TAGS_PATH + "?n=100";
        String crossOriginTags = "/redirected/authenticated-tags-final?n=100";

        stubChallenge401();
        stubTokenEndpointWithBasicAuth("user", "s3cr3t");
        wireMockServer.stubFor(get(urlEqualTo(authenticatedTags))
                .withHeader("Authorization", equalTo("Bearer " + MINTED_TOKEN))
                .willReturn(aResponse()
                        .withStatus(302)
                        .withHeader("Location", CROSS_ORIGIN_BASE_URL + crossOriginTags)));
        crossOriginWireMockServer.stubFor(get(urlEqualTo(crossOriginTags))
                .willReturn(jsonResponse(200, tagsListBody("1.25.3"))));

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.of("user"), Optional.of("s3cr3t"),
                DEFAULT_SELECTION, SEMVER_PARSER);

        VersionValue result = latestSource.version();

        assertEquals("1.25.3", result.value());
        crossOriginWireMockServer.verify(getRequestedFor(urlEqualTo(crossOriginTags))
                .withHeader("Authorization", absent()));
    }

    @Test
    void crossOriginAuthenticatedTagsRedirect_doesNotForwardBearerAuthorization_toDifferentHost() {
        String authenticatedTags = TAGS_PATH + "?n=100";
        String crossOriginTags = "/redirected/authenticated-tags-different-host?n=100";
        String differentHostBaseUrl = "http://127.0.0.1:" + PORT;

        stubChallenge401();
        stubTokenEndpointWithBasicAuth("user", "s3cr3t");
        wireMockServer.stubFor(get(urlEqualTo(authenticatedTags))
                .withHeader("Authorization", equalTo("Bearer " + MINTED_TOKEN))
                .willReturn(aResponse()
                        .withStatus(302)
                        .withHeader("Location", differentHostBaseUrl + crossOriginTags)));
        wireMockServer.stubFor(get(urlEqualTo(crossOriginTags))
                .willReturn(jsonResponse(200, tagsListBody("1.25.3"))));

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.of("user"), Optional.of("s3cr3t"),
                DEFAULT_SELECTION, SEMVER_PARSER);

        VersionValue result = latestSource.version();

        assertEquals("1.25.3", result.value());
        wireMockServer.verify(getRequestedFor(urlEqualTo(crossOriginTags))
                .withHeader("Authorization", absent()));
    }

    @Test
    void crossOriginAuthenticatedTagsRedirect_doesNotRestoreBearerAuthorization_whenChainReturnsToRegistryOrigin() {
        String authenticatedTags = TAGS_PATH + "?n=100";
        String crossOriginTags = "/redirected/authenticated-tags-cross-origin?n=100";
        String returnedTags = "/redirected/authenticated-tags-returned?n=100";

        stubChallenge401();
        stubTokenEndpointWithBasicAuth("user", "s3cr3t");
        wireMockServer.stubFor(get(urlEqualTo(authenticatedTags))
                .withHeader("Authorization", equalTo("Bearer " + MINTED_TOKEN))
                .willReturn(aResponse()
                        .withStatus(302)
                        .withHeader("Location", CROSS_ORIGIN_BASE_URL + crossOriginTags)));
        crossOriginWireMockServer.stubFor(get(urlEqualTo(crossOriginTags))
                .willReturn(aResponse()
                        .withStatus(302)
                        .withHeader("Location", BASE_URL + returnedTags)));
        wireMockServer.stubFor(get(urlEqualTo(returnedTags))
                .withHeader("Authorization", absent())
                .willReturn(jsonResponse(200, tagsListBody("1.25.3"))));

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.of("user"), Optional.of("s3cr3t"),
                DEFAULT_SELECTION, SEMVER_PARSER);

        VersionValue result = latestSource.version();

        assertEquals("1.25.3", result.value());
        crossOriginWireMockServer.verify(getRequestedFor(urlEqualTo(crossOriginTags))
                .withHeader("Authorization", absent()));
        wireMockServer.verify(getRequestedFor(urlEqualTo(returnedTags))
                .withHeader("Authorization", absent()));
    }

    @Test
    void tokenMint_retriesOnce_whenConnectionDropsAfterChallenge() {
        String expectedBasic = "Basic " + Base64.getEncoder()
                .encodeToString("user:s3cr3t".getBytes(StandardCharsets.UTF_8));
        String challenge = "Bearer realm=\"" + BASE_URL + TOKEN_PATH
                + "\",service=\"" + REGISTRY_SERVICE
                + "\",scope=\"" + CHALLENGE_SCOPE + "\"";
        String scenario = "token-mint-connection-drop";
        String recovered = "token-mint-recovered";

        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("WWW-Authenticate", challenge)));
        wireMockServer.stubFor(get(urlPathEqualTo(TOKEN_PATH))
                .withQueryParam("service", equalTo(REGISTRY_SERVICE))
                .withQueryParam("scope", equalTo(CHALLENGE_SCOPE))
                .withHeader("Authorization", equalTo(expectedBasic))
                .inScenario(scenario)
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE))
                .willSetStateTo(recovered));
        wireMockServer.stubFor(get(urlPathEqualTo(TOKEN_PATH))
                .withQueryParam("service", equalTo(REGISTRY_SERVICE))
                .withQueryParam("scope", equalTo(CHALLENGE_SCOPE))
                .withHeader("Authorization", equalTo(expectedBasic))
                .inScenario(scenario)
                .whenScenarioStateIs(recovered)
                .willReturn(jsonResponse(200, "{\"token\": \"" + MINTED_TOKEN + "\"}")));
        stubTagsListSuccess();

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.of("user"), Optional.of("s3cr3t"),
                DEFAULT_SELECTION, SEMVER_PARSER);

        VersionValue result = latestSource.version();

        assertEquals("1.25.3", result.value(),
                "a connection drop during token minting must be retried once");
        wireMockServer.verify(2, getRequestedFor(urlPathEqualTo(TOKEN_PATH))
                .withQueryParam("service", equalTo(REGISTRY_SERVICE))
                .withQueryParam("scope", equalTo(CHALLENGE_SCOPE))
                .withHeader("Authorization", equalTo(expectedBasic)));
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

    private static Stream<Integer> supportedRedirectStatuses() {
        return Stream.of(301, 302, 303, 307, 308);
    }

    private static Stream<Integer> sameOriginTokenRedirectStatuses() {
        return supportedRedirectStatuses();
    }

    private static Stream<Integer> sameOriginAuthenticatedTagsRedirectStatuses() {
        return Stream.of(301, 302, 303, 307, 308);
    }

    private static Stream<Arguments> httpsToHttpCredentialLegs() {
        return Stream.of(Arguments.of("token"), Arguments.of("tags"));
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

    private static String resourcePath(String resource) throws Exception {
        return Path.of(OciRegistryLatestSourceAuthIT.class.getClassLoader()
                .getResource(resource).toURI()).toString();
    }

    private static void restoreProperty(String name, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previousValue);
        }
    }

    private static SSLContext trustedWireMockContext() throws Exception {
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = OciRegistryLatestSourceAuthIT.class.getClassLoader()
                .getResourceAsStream(KEYSTORE_RESOURCE)) {
            trustStore.load(in, KEYSTORE_PASSWORD.toCharArray());
        }
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trustStore);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers.getTrustManagers(), new SecureRandom());
        return context;
    }
}
