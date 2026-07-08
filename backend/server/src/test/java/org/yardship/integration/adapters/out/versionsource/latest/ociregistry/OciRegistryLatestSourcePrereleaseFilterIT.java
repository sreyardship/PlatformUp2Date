package org.yardship.integration.adapters.out.versionsource.latest.ociregistry;

import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.latest.ociregistry.OciRegistryLatestSource;
import org.yardship.adapters.out.versionsource.latest.ociregistry.TagSelection;
import org.yardship.core.domain.primitives.VersionValue;

import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Integration tests for the {@code prerelease-filter} feature (slice 04) and
 * {@code strip-prerelease} feature (slice 05) of {@link OciRegistryLatestSource} (ADR-0014).
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
 *
 * <p>{@code @QuarkusTest} is required because {@link io.quarkus.rest.client.reactive.QuarkusRestClientBuilder}
 * needs a running Quarkus context.
 */
@QuarkusTest
class OciRegistryLatestSourcePrereleaseFilterIT {
    private static final VersionParser SEMVER_PARSER = new VersionParser(VersionScheme.SEMVER);


    static final int PORT = 8093;
    static final String BASE_URL = "http://localhost:" + PORT;
    static final String REPO = "library/nginx";
    static final String TAGS_PATH = "/v2/" + REPO + "/tags/list";

    static WireMockServer wireMockServer;

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
        // Tags: clean "1.0.0", both alpine variants, a non-alpine variant, and "latest".
        // filter=alpine → only "1.20.0-alpine" and "1.22.0-alpine" qualify; largest wins.
        // Expected: "1.22.0-alpine" (FULL tag, not just "1.22.0").
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .willReturn(jsonResponse(200, tagsListBody(
                        "1.0.0", "1.20.0-alpine", "1.22.0-alpine",
                        "1.22.0-alpine3.16", "1.22.0-debian", "latest"))));

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.empty(), Optional.empty(),
                new TagSelection(100, 1000, Optional.of("alpine"), false), SEMVER_PARSER);

        VersionValue result = latestSource.version();

        assertEquals("1.22.0-alpine", result.value(),
                "filter=alpine must report the largest -alpine tag as its full value");
    }

    // ---- error path --------------------------------------------------------------------------

    @Test
    void withAlpineFilter_throws_whenNoTagMatchesFilter() {
        // Registry has only clean and debian tags — no -alpine tags → per-app scrape failure.
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .willReturn(jsonResponse(200, tagsListBody(
                        "1.22.0", "1.22.0-debian", "latest"))));

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.empty(), Optional.empty(),
                new TagSelection(100, 1000, Optional.of("alpine"), false), SEMVER_PARSER);

        assertThrows(RuntimeException.class, latestSource::version,
                "no tag matches filter=alpine → must throw as a per-app scrape failure");
    }

    // ---- strip-prerelease (slice 05) ---------------------------------------------------------

    @Test
    void withAlpineFilter_andStripPrerelease_reportsStrippedVersion() {
        // filter=alpine + strip=true:
        //   - SELECTION: picks the largest -alpine tag → "1.22.0-alpine" (full tag, correct ranking)
        //   - REPORT:    strips the prerelease → "1.22.0"
        // Without the strip implementation the source still returns "1.22.0-alpine" → test fails red.
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .willReturn(jsonResponse(200, tagsListBody(
                        "1.0.0", "1.20.0-alpine", "1.22.0-alpine",
                        "1.22.0-alpine3.16", "1.22.0-debian", "latest"))));

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.empty(), Optional.empty(),
                new TagSelection(100, 1000, Optional.of("alpine"), true), SEMVER_PARSER);

        VersionValue result = latestSource.version();

        assertEquals("1.22.0", result.value(),
                "filter=alpine strip=true: selection picks 1.22.0-alpine, stripped to 1.22.0");
    }

    // ---- helpers -------------------------------------------------------------------------------

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
