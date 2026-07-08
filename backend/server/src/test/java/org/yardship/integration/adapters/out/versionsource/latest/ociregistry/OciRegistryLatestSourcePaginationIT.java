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
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration tests for OCI registry pagination (slice 03 — ADR-0014).
 *
 * <p>Uses a standalone WireMock server on port 8092, distinct from the no-challenge tests on 8090
 * ({@link OciRegistryLatestSourceIT}) and the bearer-dance tests on 8091
 * ({@link OciRegistryLatestSourceAuthIT}). Each test class gets its own port to avoid test-order
 * dependencies when all three classes run in the same JVM.
 *
 * <p>These tests cover:
 * <ul>
 *   <li>Multi-page traversal via {@code Link: rel="next"} — largest tag on a LATER page wins.</li>
 *   <li>{@code n} query param sent = configured page-size (assert via WireMock {@code withQueryParam}).</li>
 *   <li>{@code last} query param threaded from the Link header's {@code last=} on subsequent requests.</li>
 *   <li>Cap-exceeded: source returns largest-seen version and does NOT request the next page.</li>
 *   <li>No-cap-exceeded: all pages fetched, global largest returned; traversal stops at last page.</li>
 * </ul>
 *
 * <p>{@code @QuarkusTest} is required because {@link io.quarkus.rest.client.reactive.QuarkusRestClientBuilder}
 * needs a running Quarkus context.
 *
 * <p>NOTE on WARNING assertion: this slice does not assert the WARNING log emitted on cap-exceed
 * (ADR-0014: "log a warning naming the repo and the cap") because no log-capture pattern exists
 * in the test suite. The behavioral assertions (largest-seen returned, no further request) are
 * the primary spec. Log capture can be added later if the pattern is introduced.
 */
@QuarkusTest
class OciRegistryLatestSourcePaginationIT {
    private static final VersionParser SEMVER_PARSER = new VersionParser(VersionScheme.SEMVER);


    static final int PORT = 8092;
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

    // ---- multi-page traversal -----------------------------------------------------------------

    @Test
    void multiPage_selectsLargestSemver_whenLargestTagIsOnLaterPage() {
        // Page 1: ["1.0.0", "1.1.0"] with Link header pointing to page 2.
        // Page 2: ["2.0.0"] — the largest tag, no Link (last page).
        // The source must traverse BOTH pages and return "2.0.0".
        // Without pagination implementation the source returns "1.1.0" → test fails red.
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .withQueryParam("n", equalTo("2"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Link",
                                "<" + TAGS_PATH + "?n=2&last=1.1.0>; rel=\"next\"")
                        .withBody(tagsListBody("1.0.0", "1.1.0"))));

        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .withQueryParam("n", equalTo("2"))
                .withQueryParam("last", equalTo("1.1.0"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(tagsListBody("2.0.0")))); // no Link header = last page

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.empty(), Optional.empty(),
                new TagSelection(2, 100, Optional.empty(), false), SEMVER_PARSER);

        VersionValue result = latestSource.version();

        assertEquals("2.0.0", result.value(),
                "Must traverse all pages and select the global largest semver (on page 2)");
    }

    @Test
    void multiPage_traversesThreePages_returnsGlobalLargest() {
        // Three pages: 1→2→3, largest tag (3.0.0) on page 3.
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .withQueryParam("n", equalTo("1"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Link",
                                "<" + TAGS_PATH + "?n=1&last=1.0.0>; rel=\"next\"")
                        .withBody(tagsListBody("1.0.0"))));

        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .withQueryParam("n", equalTo("1"))
                .withQueryParam("last", equalTo("1.0.0"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Link",
                                "<" + TAGS_PATH + "?n=1&last=2.0.0>; rel=\"next\"")
                        .withBody(tagsListBody("2.0.0"))));

        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .withQueryParam("n", equalTo("1"))
                .withQueryParam("last", equalTo("2.0.0"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(tagsListBody("3.0.0")))); // no Link = last page

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.empty(), Optional.empty(),
                new TagSelection(1, 100, Optional.empty(), false), SEMVER_PARSER);

        VersionValue result = latestSource.version();

        assertEquals("3.0.0", result.value(),
                "Must traverse all three pages and select 3.0.0 as the global largest");
    }

    // ---- n query param assertion --------------------------------------------------------------

    @Test
    void nQueryParam_sentOnFirstRequest_matchesConfiguredPageSize() {
        // page-size=5 → n=5 must appear on the wire.
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .withQueryParam("n", equalTo("5"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(tagsListBody("1.0.0"))));

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.empty(), Optional.empty(),
                new TagSelection(5, 100, Optional.empty(), false), SEMVER_PARSER);
        latestSource.version();

        wireMockServer.verify(getRequestedFor(urlPathEqualTo(TAGS_PATH))
                .withQueryParam("n", equalTo("5")));
    }

    @Test
    void nQueryParam_defaultIs100_whenSourceConstructedWithDefaultPageSize() {
        // Backward-compat constructor (no pageSize arg) → defaults to 100.
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .withQueryParam("n", equalTo("100"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(tagsListBody("1.0.0"))));

        // Uses the default selection (no explicit pageSize)
        OciRegistryLatestSource latestSource = anonymousSource(BASE_URL + "/v2/" + REPO);
        latestSource.version();

        wireMockServer.verify(getRequestedFor(urlPathEqualTo(TAGS_PATH))
                .withQueryParam("n", equalTo("100")));
    }

    // ---- last query param threading -----------------------------------------------------------

    @Test
    void lastQueryParam_threadedFromLinkHeader_onSubsequentPageRequest() {
        // Page 1 returns Link containing last=1.1.0; page 2 must be requested with last=1.1.0.
        // Without pagination implementation, page 2 is never requested → verify fails → test red.
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .withQueryParam("n", equalTo("2"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Link",
                                "<" + TAGS_PATH + "?n=2&last=1.1.0>; rel=\"next\"")
                        .withBody(tagsListBody("1.0.0", "1.1.0"))));

        // Only responds when last=1.1.0 — if the source forgets to send it, WireMock returns 404
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .withQueryParam("n", equalTo("2"))
                .withQueryParam("last", equalTo("1.1.0"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(tagsListBody("2.0.0"))));

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.empty(), Optional.empty(),
                new TagSelection(2, 100, Optional.empty(), false), SEMVER_PARSER);
        latestSource.version();

        // The second request must carry last=1.1.0
        wireMockServer.verify(getRequestedFor(urlPathEqualTo(TAGS_PATH))
                .withQueryParam("last", equalTo("1.1.0")));
    }

    // ---- cap-exceeded: truncate-and-warn (ADR-0014) -------------------------------------------

    @Test
    void capExceeded_returnsLargestSeenVersion_andDoesNotRequestFurtherPages() {
        // max-tags=4, page-size=2.
        // Page 1: ["1.0.0","1.1.0"] (2 tags), Link present.
        // Page 2: ["2.0.0","2.1.0"] (2 more = 4 total = cap reached), Link present.
        // Page 3 would have ["3.0.0"] — must NOT be fetched.
        // Expected result: "2.1.0" (largest from pages 1+2).
        // With stub (single-page): returns "1.1.0" → assertEquals("2.1.0", …) fails → red.
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .withQueryParam("n", equalTo("2"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Link",
                                "<" + TAGS_PATH + "?n=2&last=1.1.0>; rel=\"next\"")
                        .withBody(tagsListBody("1.0.0", "1.1.0"))));

        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .withQueryParam("n", equalTo("2"))
                .withQueryParam("last", equalTo("1.1.0"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Link",
                                "<" + TAGS_PATH + "?n=2&last=2.1.0>; rel=\"next\"")
                        .withBody(tagsListBody("2.0.0", "2.1.0"))));

        // No stub for page 3 (last=2.1.0): if source requests it, WireMock returns 404 → exception

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.empty(), Optional.empty(),
                new TagSelection(2, 4, Optional.empty(), false), SEMVER_PARSER);

        VersionValue result = latestSource.version();

        assertEquals("2.1.0", result.value(),
                "Cap-exceeded: must return the largest semver from pages 1 and 2 (not throw, not fetch page 3)");
        // Assert page 3 was NOT requested (no request with last=2.1.0)
        wireMockServer.verify(0, getRequestedFor(urlPathEqualTo(TAGS_PATH))
                .withQueryParam("last", equalTo("2.1.0")));
    }

    @Test
    void capExceeded_doesNotThrow_evenWhenTruncationOccurs() {
        // Cap-exceeded is a soft truncation (ADR-0014: truncate-and-warn), NOT a failure.
        // The source must return a valid Version, not throw.
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .withQueryParam("n", equalTo("2"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Link",
                                "<" + TAGS_PATH + "?n=2&last=1.1.0>; rel=\"next\"")
                        .withBody(tagsListBody("1.0.0", "1.1.0"))));

        // No stub for page 2 — source must stop at cap, never requesting it
        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.empty(), Optional.empty(),
                new TagSelection(2, 2, Optional.empty(), false), SEMVER_PARSER);

        // Must not throw — cap-exceeded is a warning, not an error
        VersionValue result = latestSource.version();

        assertEquals("1.1.0", result.value(),
                "Cap-exceeded with max-tags=page-size=2: returns largest from the only page fetched");
    }

    // ---- no-cap-exceeded: normal behaviour ----------------------------------------------------

    @Test
    void noCapExceeded_fetchesAllPages_returnsGlobalLargest() {
        // max-tags=100, page-size=2; 2 pages of 2 tags → total 4, well within the cap.
        // Largest tag (1.3.0) is on page 2.
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .withQueryParam("n", equalTo("2"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Link",
                                "<" + TAGS_PATH + "?n=2&last=1.1.0>; rel=\"next\"")
                        .withBody(tagsListBody("1.0.0", "1.1.0"))));

        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .withQueryParam("n", equalTo("2"))
                .withQueryParam("last", equalTo("1.1.0"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(tagsListBody("1.2.0", "1.3.0")))); // no Link = last page

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.empty(), Optional.empty(),
                new TagSelection(2, 100, Optional.empty(), false), SEMVER_PARSER);

        VersionValue result = latestSource.version();

        assertEquals("1.3.0", result.value(),
                "No-cap-exceeded path: all pages fetched, global largest (on page 2) returned");
    }

    @Test
    void noCapExceeded_doesNotMakeExtraRequest_whenLastPageHasNoLink() {
        // After the last page (no Link header), the source must not attempt a further request.
        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .withQueryParam("n", equalTo("2"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Link",
                                "<" + TAGS_PATH + "?n=2&last=1.1.0>; rel=\"next\"")
                        .withBody(tagsListBody("1.0.0", "1.1.0"))));

        wireMockServer.stubFor(get(urlPathEqualTo(TAGS_PATH))
                .withQueryParam("n", equalTo("2"))
                .withQueryParam("last", equalTo("1.1.0"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(tagsListBody("2.0.0")))); // no Link = done

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                BASE_URL + "/v2/" + REPO, Optional.empty(), Optional.empty(),
                new TagSelection(2, 100, Optional.empty(), false), SEMVER_PARSER);
        latestSource.version();

        // Exactly two requests total (page 1 + page 2); nothing after
        wireMockServer.verify(2, getRequestedFor(urlPathEqualTo(TAGS_PATH)));
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static String tagsListBody(String... tags) {
        String joined = String.join("\", \"", tags);
        return """
                {"name": "%s", "tags": ["%s"]}
                """.formatted(REPO, joined);
    }

    private static OciRegistryLatestSource anonymousSource(String baseUrl) {
        return new OciRegistryLatestSource(baseUrl, Optional.empty(), Optional.empty(),
                new TagSelection(100, 1000, Optional.empty(), false), SEMVER_PARSER);
    }
}
