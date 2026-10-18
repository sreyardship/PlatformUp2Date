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
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration tests for OCI registry pagination (ADR-0014), built end to end through
 * {@link OciRegistryLatestSourceFactory#create} so {@code page-size} and {@code max-tags} are read
 * from a real config fragment. Plain JUnit.
 *
 * <p>Uses a standalone WireMock server on port 8092, distinct from the no-challenge tests on 8090
 * ({@link OciRegistryLatestSourceIT}) and the bearer-dance tests on 8091
 * ({@link OciRegistryLatestSourceAuthIT}). Each test class gets its own port to avoid test-order
 * dependencies when all three classes run in the same JVM.
 *
 * <p>These tests cover:
 * <ul>
 *   <li>Multi-page traversal via {@code Link: rel="next"} — largest tag on a LATER page wins.</li>
 *   <li>{@code n} query param sent = configured page-size (default 100).</li>
 *   <li>The next page is requested at the {@code Link} target, so {@code last=} is threaded.</li>
 *   <li>Cap-exceeded: source returns largest-seen version and does NOT request the next page.</li>
 *   <li>No-cap-exceeded: all pages fetched, global largest returned; traversal stops at last page.</li>
 * </ul>
 *
 * <p>These tests do not assert the warning log emitted when the cap is exceeded
 * (ADR-0014: "log a warning naming the repo and the cap") because no log-capture pattern exists
 * in the test suite. The behavioral assertions (largest-seen returned, no further request) are
 * the primary spec.
 */
class OciRegistryLatestSourcePaginationIT {
    private static final VersionParser SEMVER_PARSER = new VersionParser(VersionScheme.SEMVER);

    static final int PORT = 8092;
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

    // ---- multi-page traversal -----------------------------------------------------------------

    @Test
    void multiPage_selectsLargestSemver_whenLargestTagIsOnLaterPage() {
        stubPage("2", null, "<" + TAGS_PATH + "?n=2&last=1.1.0>; rel=\"next\"", "1.0.0", "1.1.0");
        stubPage("2", "1.1.0", null, "2.0.0"); // no Link header = last page

        VersionValue result = source(2, 100).version();

        assertEquals("2.0.0", result.value(),
                "Must traverse all pages and select the global largest semver (on page 2)");
    }

    @Test
    void multiPage_traversesThreePages_returnsGlobalLargest() {
        stubPage("1", null, "<" + TAGS_PATH + "?n=1&last=1.0.0>; rel=\"next\"", "1.0.0");
        stubPage("1", "1.0.0", "<" + TAGS_PATH + "?n=1&last=2.0.0>; rel=\"next\"", "2.0.0");
        stubPage("1", "2.0.0", null, "3.0.0"); // no Link = last page

        VersionValue result = source(1, 100).version();

        assertEquals("3.0.0", result.value(),
                "Must traverse all three pages and select 3.0.0 as the global largest");
    }

    // ---- n query param ---------------------------------------------------------------------------

    @Test
    void nQueryParam_sentOnFirstRequest_matchesConfiguredPageSize() {
        stubPage("5", null, null, "1.0.0");

        source(5, 100).version();

        wireMockServer.verify(getRequestedFor(urlPathEqualTo(TAGS_PATH))
                .withQueryParam("n", equalTo("5")));
    }

    @Test
    void nQueryParam_defaultIs100_whenTheFragmentHasNoPageSize() {
        stubPage("100", null, null, "1.0.0");

        factory.create(fragment(), SEMVER_PARSER).version();

        wireMockServer.verify(getRequestedFor(urlPathEqualTo(TAGS_PATH))
                .withQueryParam("n", equalTo("100")));
    }

    // ---- the next page is the Link target -------------------------------------------------------

    @Test
    void nextPage_isRequestedAtTheLinkTarget_carryingItsLastCursor() {
        stubPage("2", null, "<" + TAGS_PATH + "?n=2&last=1.1.0>; rel=\"next\"", "1.0.0", "1.1.0");
        // Only answers with last=1.1.0 — if the source rebuilt the URL itself, WireMock returns 404.
        stubPage("2", "1.1.0", null, "2.0.0");

        source(2, 100).version();

        wireMockServer.verify(getRequestedFor(urlPathEqualTo(TAGS_PATH))
                .withQueryParam("last", equalTo("1.1.0")));
    }

    // ---- cap-exceeded: truncate-and-warn (ADR-0014) -------------------------------------------

    @Test
    void capExceeded_returnsLargestSeenVersion_andDoesNotRequestFurtherPages() {
        // max-tags=4, page-size=2: pages 1 and 2 reach the cap with a next link still present;
        // page 3 (which would hold 3.0.0) must NOT be fetched. No stub for it: a fetch would 404.
        stubPage("2", null, "<" + TAGS_PATH + "?n=2&last=1.1.0>; rel=\"next\"", "1.0.0", "1.1.0");
        stubPage("2", "1.1.0", "<" + TAGS_PATH + "?n=2&last=2.1.0>; rel=\"next\"", "2.0.0", "2.1.0");

        VersionValue result = source(2, 4).version();

        assertEquals("2.1.0", result.value(),
                "Cap-exceeded: must return the largest semver from pages 1 and 2 (not throw, not fetch page 3)");
        wireMockServer.verify(0, getRequestedFor(urlPathEqualTo(TAGS_PATH))
                .withQueryParam("last", equalTo("2.1.0")));
    }

    @Test
    void capExceeded_doesNotThrow_evenWhenTruncationOccurs() {
        // Cap-exceeded is a soft truncation (ADR-0014: truncate-and-warn), NOT a failure.
        stubPage("2", null, "<" + TAGS_PATH + "?n=2&last=1.1.0>; rel=\"next\"", "1.0.0", "1.1.0");
        // No stub for page 2 — the source must stop at the cap, never requesting it.

        VersionValue result = source(2, 2).version();

        assertEquals("1.1.0", result.value(),
                "Cap-exceeded with max-tags=page-size=2: returns largest from the only page fetched");
    }

    // ---- no-cap-exceeded: normal behaviour ----------------------------------------------------

    @Test
    void noCapExceeded_fetchesAllPages_returnsGlobalLargest() {
        stubPage("2", null, "<" + TAGS_PATH + "?n=2&last=1.1.0>; rel=\"next\"", "1.0.0", "1.1.0");
        stubPage("2", "1.1.0", null, "1.2.0", "1.3.0"); // no Link = last page

        VersionValue result = source(2, 100).version();

        assertEquals("1.3.0", result.value(),
                "No-cap-exceeded path: all pages fetched, global largest (on page 2) returned");
    }

    @Test
    void noCapExceeded_doesNotMakeExtraRequest_whenLastPageHasNoLink() {
        stubPage("2", null, "<" + TAGS_PATH + "?n=2&last=1.1.0>; rel=\"next\"", "1.0.0", "1.1.0");
        stubPage("2", "1.1.0", null, "2.0.0"); // no Link = done

        source(2, 100).version();

        wireMockServer.verify(2, getRequestedFor(urlPathEqualTo(TAGS_PATH)));
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** One {@code tags/list} page: matched on {@code n} and (when non-null) {@code last}; {@code link} may be null. */
    private static void stubPage(String n, String last, String link, String... tags) {
        var mapping = get(urlPathEqualTo(TAGS_PATH)).withQueryParam("n", equalTo(n));
        if (last != null) {
            mapping = mapping.withQueryParam("last", equalTo(last));
        }
        var response = aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(tagsListBody(tags));
        if (link != null) {
            response = response.withHeader("Link", link);
        }
        wireMockServer.stubFor(mapping.willReturn(response));
    }

    private static String tagsListBody(String... tags) {
        String joined = String.join("\", \"", tags);
        return """
                {"name": "%s", "tags": ["%s"]}
                """.formatted(REPO, joined);
    }

    private LatestVersionSource source(int pageSize, int maxTags) {
        return factory.create(fragment().pageSize(pageSize).maxTags(maxTags), SEMVER_PARSER);
    }

    private static LatestSourceFragment fragment() {
        return LatestSourceFragment.ofType("oci-registry").registry(BASE_URL).repo(REPO);
    }
}
