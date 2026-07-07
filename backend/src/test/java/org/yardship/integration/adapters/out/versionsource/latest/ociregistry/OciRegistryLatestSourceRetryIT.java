package org.yardship.integration.adapters.out.versionsource.latest.ociregistry;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.latest.ociregistry.OciRegistryLatestSource;
import org.yardship.adapters.out.versionsource.latest.ociregistry.TagSelection;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import org.yardship.core.domain.primitives.VersionValue;

import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Integration tests for the connection-failure retry in {@link OciRegistryLatestSource}: every
 * call the source makes is an idempotent GET, so a transport-level failure (the HTTP/1.1
 * keep-alive close race — the server closing a connection at the same moment the client sends a
 * request on it, RFC 9112 §9.3.1) is retried exactly once before surfacing as a scrape failure.
 *
 * <p>Uses a standalone WireMock server on port 8094, distinct from the no-challenge tests on 8090
 * ({@link OciRegistryLatestSourceIT}), the bearer-dance tests on 8091
 * ({@link OciRegistryLatestSourceAuthIT}), the pagination tests on 8092
 * ({@link OciRegistryLatestSourcePaginationIT}), and the prerelease-filter tests on 8093
 * ({@link OciRegistryLatestSourcePrereleaseFilterIT}).
 *
 * <p>Connection failures are simulated with WireMock faults inside a scenario: the first request
 * gets the connection torn down mid-response, the next request gets a clean answer — the same
 * shape a real registry (or LB in front of one) produces when it drops a connection.
 *
 * <p>{@code @QuarkusTest} is required because {@code QuarkusRestClientBuilder} needs a running
 * Quarkus context — matching the existing IT style.
 */
@QuarkusTest
class OciRegistryLatestSourceRetryIT {
    private static final VersionParser SEMVER_PARSER = new VersionParser(VersionScheme.SEMVER);

    static final int PORT = 8094;
    static final String BASE_URL = "http://localhost:" + PORT;
    static final String REPO = "library/nginx";
    static final String TAGS_PATH = "/v2/" + REPO + "/tags/list";
    static final String SCENARIO = "flaky-connection";
    static final String RECOVERED = "recovered";

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

    @Test
    void version_retriesOnce_whenConnectionDropsOnFirstAttempt() {
        // First request: server kills the connection without a response (what the client sees in
        // the keep-alive close race). Second request: clean 200.
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH)).inScenario(SCENARIO)
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE))
                .willSetStateTo(RECOVERED));
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH)).inScenario(SCENARIO)
                .whenScenarioStateIs(RECOVERED)
                .willReturn(jsonResponse("""
                        {"name": "%s", "tags": ["1.25.3"]}
                        """.formatted(REPO))));

        OciRegistryLatestSource latestSource = anonymousSource();

        VersionValue result = latestSource.version();

        assertEquals("1.25.3", result.value(),
                "a single connection drop must be absorbed by the idempotent-GET retry");
    }

    @Test
    void version_retriesOnce_whenConnectionResetsOnFirstAttempt() {
        // Same contract for a mid-flight TCP reset instead of a clean close.
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH)).inScenario(SCENARIO)
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))
                .willSetStateTo(RECOVERED));
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH)).inScenario(SCENARIO)
                .whenScenarioStateIs(RECOVERED)
                .willReturn(jsonResponse("""
                        {"name": "%s", "tags": ["2.0.1"]}
                        """.formatted(REPO))));

        OciRegistryLatestSource latestSource = anonymousSource();

        VersionValue result = latestSource.version();

        assertEquals("2.0.1", result.value(),
                "a single connection reset must be absorbed by the idempotent-GET retry");
    }

    @Test
    void version_failsAfterSecondConnectionFailure_noEndlessRetry() {
        // Both attempts get the connection torn down — the failure must surface (exactly one
        // retry), not loop.
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));

        OciRegistryLatestSource latestSource = anonymousSource();

        assertThrows(RuntimeException.class, latestSource::version,
                "a persistently failing connection must surface as a scrape failure after one retry");
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder jsonResponse(
            String body) {
        return aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(body);
    }

    private static OciRegistryLatestSource anonymousSource() {
        return new OciRegistryLatestSource(BASE_URL + "/v2/" + REPO,
                Optional.empty(), Optional.empty(), DEFAULT_SELECTION, SEMVER_PARSER);
    }
}
