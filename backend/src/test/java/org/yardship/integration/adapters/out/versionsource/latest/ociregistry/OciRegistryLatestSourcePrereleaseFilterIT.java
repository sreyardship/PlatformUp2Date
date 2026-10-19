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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Integration tests for the {@code prerelease-filter} and {@code strip-prerelease} behavior of the
 * {@code oci-registry} kind (ADR-0014), built end to end through
 * {@link OciRegistryLatestSourceFactory#create} so both knobs are read from a real config fragment.
 * Plain JUnit.
 *
 * <p>Uses a standalone WireMock server on port 8093, distinct from the no-challenge tests on 8090
 * ({@link OciRegistryLatestSourceIT}), the bearer-dance tests on 8091
 * ({@link OciRegistryLatestSourceAuthIT}), and the pagination tests on 8092
 * ({@link OciRegistryLatestSourcePaginationIT}).
 *
 * <p>Covers the fetch-and-select happy path confirming that the filter and strip-prerelease knobs
 * are wired correctly through the HTTP layer, plus the no-match error path. Exact-match semantics,
 * ranking, and strip logic are owned by
 * {@link org.yardship.unit.adapters.out.versionsource.latest.ociregistry.OciTagSelectorTests}.
 */
class OciRegistryLatestSourcePrereleaseFilterIT {
    private static final VersionParser SEMVER_PARSER = new VersionParser(VersionScheme.SEMVER);

    static final int PORT = 8093;
    static final String BASE_URL = "http://localhost:" + PORT;
    static final String REPO = "library/nginx";
    static final String TAGS_PATH = "/v2/" + REPO + "/tags/list";

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

    // ---- happy path (filter, no strip) -------------------------------------------------------

    @Test
    void withAlpineFilter_selectsLargestAlpineTag_andReportsFullTagValue() {
        // filter=alpine → only "1.20.0-alpine" and "1.22.0-alpine" qualify; largest wins, reported
        // as the FULL tag, not just "1.22.0".
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .willReturn(jsonResponse(200, tagsListBody(
                        "1.0.0", "1.20.0-alpine", "1.22.0-alpine",
                        "1.22.0-alpine3.16", "1.22.0-debian", "latest"))));

        VersionValue result = source("alpine", false).version();

        assertEquals("1.22.0-alpine", result.value(),
                "filter=alpine must report the largest -alpine tag as its full value");
    }

    // ---- error path --------------------------------------------------------------------------

    @Test
    void withAlpineFilter_throws_whenNoTagMatchesFilter() {
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .willReturn(jsonResponse(200, tagsListBody(
                        "1.22.0", "1.22.0-debian", "latest"))));

        LatestVersionSource latestSource = source("alpine", false);

        assertThrows(RuntimeException.class, latestSource::version,
                "no tag matches filter=alpine → must throw as a per-app scrape failure");
    }

    // ---- strip-prerelease -------------------------------------------------------------------

    @Test
    void withAlpineFilter_andStripPrerelease_reportsStrippedVersion() {
        // SELECTION picks the largest -alpine tag ("1.22.0-alpine"); REPORT strips it to "1.22.0".
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .willReturn(jsonResponse(200, tagsListBody(
                        "1.0.0", "1.20.0-alpine", "1.22.0-alpine",
                        "1.22.0-alpine3.16", "1.22.0-debian", "latest"))));

        VersionValue result = source("alpine", true).version();

        assertEquals("1.22.0", result.value(),
                "filter=alpine strip=true: selection picks 1.22.0-alpine, stripped to 1.22.0");
    }

    // ---- helpers -------------------------------------------------------------------------------

    private LatestVersionSource source(String prereleaseFilter, boolean stripPrerelease) {
        return factory.create(
                LatestSourceFragment.ofType("oci-registry").registry(BASE_URL).repo(REPO)
                        .prereleaseFilter(prereleaseFilter).stripPrerelease(stripPrerelease),
                SEMVER_PARSER);
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
