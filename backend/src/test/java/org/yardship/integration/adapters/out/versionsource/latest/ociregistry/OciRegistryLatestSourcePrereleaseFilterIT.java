package org.yardship.integration.adapters.out.versionsource.latest.ociregistry;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.latest.ociregistry.OciRegistryLatestSource;
import org.yardship.adapters.out.versionsource.latest.ociregistry.TagSelection;
import org.yardship.core.domain.primitives.Version;

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
 * <p>Covers (filter + no-strip):
 * <ul>
 *   <li>With {@code prerelease-filter: alpine}, the largest {@code …-alpine} tag is reported as
 *       the FULL tag value (e.g. {@code 1.22.0-alpine}).</li>
 *   <li>{@code alpine} does NOT match {@code 1.22.0-alpine3.16} (exact, not prefix).</li>
 *   <li>Clean semver tags are not selected when a filter is configured.</li>
 *   <li>An image with no tag matching the filter throws — per-app scrape failure.</li>
 *   <li>Without a filter, the original clean-semver behaviour is preserved.</li>
 * </ul>
 *
 * <p>Covers (filter + strip-prerelease=true — slice 05):
 * <ul>
 *   <li>filter=alpine + strip=true: selection still picks the LARGEST {@code -alpine} tag by full
 *       tag value, but the REPORTED result is the stripped core (e.g. {@code 1.22.0}).</li>
 * </ul>
 *
 * <p>{@code @QuarkusTest} is required because {@link io.quarkus.rest.client.reactive.QuarkusRestClientBuilder}
 * needs a running Quarkus context.
 */
@QuarkusTest
class OciRegistryLatestSourcePrereleaseFilterIT {

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
                new TagSelection(100, 1000, Optional.of("alpine"), false));

        Version result = latestSource.version();

        assertEquals("1.22.0-alpine", result.value(),
                "filter=alpine must report the largest -alpine tag as its full value");
    }

    @Test
    void withAlpineFilter_doesNotSelectAlpine316_exactMatchOnly() {
        // "1.22.0-alpine3.16" must NOT satisfy filter="alpine" — exact, not prefix.
        // Only "1.20.0-alpine" matches → result is "1.20.0-alpine".
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .willReturn(jsonResponse(200, tagsListBody(
                        "1.20.0-alpine", "1.22.0-alpine3.16", "latest"))));

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.empty(), Optional.empty(),
                new TagSelection(100, 1000, Optional.of("alpine"), false));

        Version result = latestSource.version();

        assertEquals("1.20.0-alpine", result.value(),
                "filter=alpine must not match 1.22.0-alpine3.16 (exact, not prefix)");
    }

    @Test
    void withAlpine316Filter_selectsAlpine316_andNotBareAlpine() {
        // filter="alpine3.16" selects "1.22.0-alpine3.16" but NOT "1.20.0-alpine".
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .willReturn(jsonResponse(200, tagsListBody(
                        "1.20.0-alpine", "1.22.0-alpine3.16", "1.21.0-alpine3.16"))));

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.empty(), Optional.empty(),
                new TagSelection(100, 1000, Optional.of("alpine3.16"), false));

        Version result = latestSource.version();

        assertEquals("1.22.0-alpine3.16", result.value(),
                "filter=alpine3.16 selects the largest -alpine3.16 tag");
    }

    @Test
    void withAlpineFilter_skipsCleanTags_evenWhenLarger() {
        // Clean tag "2.0.0" is numerically larger than any "-alpine" tag,
        // but with a filter set, clean tags must be skipped.
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .willReturn(jsonResponse(200, tagsListBody(
                        "2.0.0", "1.22.0-alpine", "1.20.0-alpine"))));

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.empty(), Optional.empty(),
                new TagSelection(100, 1000, Optional.of("alpine"), false));

        Version result = latestSource.version();

        assertEquals("1.22.0-alpine", result.value(),
                "filter=alpine: clean 2.0.0 is ignored even though numerically larger");
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
                new TagSelection(100, 1000, Optional.of("alpine"), false));

        assertThrows(RuntimeException.class, latestSource::version,
                "no tag matches filter=alpine → must throw as a per-app scrape failure");
    }

    // ---- regression: no filter → original behaviour unchanged --------------------------------

    @Test
    void withoutFilter_cleanSemverWins_variantsSkipped_regressionGuard() {
        // Without a filter the original slice-01 behaviour must be preserved:
        // largest clean semver wins; variant tags (prerelease) are skipped.
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .willReturn(jsonResponse(200, tagsListBody(
                        "1.24.0", "1.25.3", "1.25.3-alpine", "latest", "stable"))));

        // Use the backward-compat 1-arg constructor (no filter).
        OciRegistryLatestSource latestSource =
                new OciRegistryLatestSource(BASE_URL + "/v2/" + REPO);

        Version result = latestSource.version();

        assertEquals("1.25.3", result.value(),
                "Without filter: clean 1.25.3 must win; 1.25.3-alpine and non-semver tags are skipped");
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
                new TagSelection(100, 1000, Optional.of("alpine"), true));

        Version result = latestSource.version();

        assertEquals("1.22.0", result.value(),
                "filter=alpine strip=true: selection picks 1.22.0-alpine, stripped to 1.22.0");
    }

    @Test
    void withAlpineFilter_andStripPrerelease_ranksByFullTag_thenStrips() {
        // Ranking must still be by the FULL tag (1.24.0-alpine > 1.22.0-alpine by core version),
        // and only the reported result is stripped — so the answer is 1.24.0, not 1.22.0.
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .willReturn(jsonResponse(200, tagsListBody(
                        "1.20.0-alpine", "1.24.0-alpine", "1.22.0-alpine"))));

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.empty(), Optional.empty(),
                new TagSelection(100, 1000, Optional.of("alpine"), true));

        Version result = latestSource.version();

        assertEquals("1.24.0", result.value(),
                "filter=alpine strip=true: 1.24.0-alpine is the largest, reported stripped as 1.24.0");
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
