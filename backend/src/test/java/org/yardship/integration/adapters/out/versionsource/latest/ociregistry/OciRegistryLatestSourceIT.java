package org.yardship.integration.adapters.out.versionsource.latest.ociregistry;

import org.yardship.adapters.out.versionsource.latest.ociregistry.TagSelection;
import java.util.Optional;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.latest.ociregistry.OciRegistryLatestSource;
import org.yardship.core.domain.primitives.VersionValue;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Integration test for the real {@link OciRegistryLatestSource} adapter against a standalone
 * WireMock server on port 8090. This mirrors the style of
 * {@link org.yardship.integration.adapters.out.versionsource.latest.githubrelease.GithubReleaseLatestSourceIT}.
 *
 * <p>The source is constructed directly (plain object) with a base URL of the form
 * {@code http://localhost:8090/v2/{repo}}, which is the URL the factory would assemble from
 * {@code registry=http://localhost:8090} and {@code repo=library/nginx}.
 *
 * <p>{@code @QuarkusTest} is required because {@code QuarkusRestClientBuilder} needs a running
 * Quarkus context — matching the existing IT style.
 *
 * <p>This class covers the fetch-and-select round-trip: single-page tags/list (no challenge),
 * real Jackson deserialization, the all-skipped error path, and the HTTP endpoint path.
 * Tag-selection logic (ranking, filter, strip) is owned by
 * {@link org.yardship.unit.adapters.out.versionsource.latest.ociregistry.OciTagSelectorTests}.
 */
@QuarkusTest
class OciRegistryLatestSourceIT {
    private static final VersionParser SEMVER_PARSER = new VersionParser(VersionScheme.SEMVER);


    static WireMockServer wireMockServer;

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(options().port(8090));
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
    void read_skipsNonSemverTags_andPrereleaseVariantTags() {
        wireMockServer.stubFor(get(urlPathEqualTo("/v2/library/redis/tags/list"))
                .willReturn(jsonResponse(200, """
                        {
                          "name": "library/redis",
                          "tags": ["7.0.0", "7.0.0-alpine", "7.2.0-rc1", "latest", "7.2.0", "edge"]
                        }
                        """)));

        OciRegistryLatestSource latestSource =
                anonymousSource("http://localhost:8090/v2/library/redis");

        VersionValue result = latestSource.version();

        assertEquals("7.2.0", result.value(),
                "7.2.0 is the largest clean semver; variant and non-semver tags are skipped");
    }

    @Test
    void read_throws_whenAllTagsAreSkipped() {
        wireMockServer.stubFor(get(urlPathEqualTo("/v2/library/scratch/tags/list"))
                .willReturn(jsonResponse(200, """
                        {
                          "name": "library/scratch",
                          "tags": ["latest", "edge", "1.0.0-alpine"]
                        }
                        """)));

        OciRegistryLatestSource latestSource =
                anonymousSource("http://localhost:8090/v2/library/scratch");

        assertThrows(RuntimeException.class, latestSource::version,
                "an all-skipped tag set must surface as a per-app scrape failure, not a silent return");
    }

    @Test
    void read_callsTagsListEndpoint_withCorrectPath() {
        wireMockServer.stubFor(get(urlPathEqualTo("/v2/library/nginx/tags/list"))
                .willReturn(jsonResponse(200, """
                        {
                          "name": "library/nginx",
                          "tags": ["1.0.0"]
                        }
                        """)));

        OciRegistryLatestSource latestSource =
                anonymousSource("http://localhost:8090/v2/library/nginx");
        latestSource.version();

        wireMockServer.verify(getRequestedFor(urlPathEqualTo("/v2/library/nginx/tags/list")));
    }

    // ---- anonymous redirect handling (ADR-0029) -----------------------------------------------
    // The raw (unauthenticated) tags/list request must follow 301/302/303/307/308 to a canonical
    // endpoint that returns 200, and still produce the correct pagination/selection result. These
    // tests cover the direct-200/no-challenge branch; OciRegistryLatestSourceAuthIT covers
    // redirect-to-401 challenge flows.

    @Test
    void redirect_followsToCanonicalEndpoint_andSelectsLargestTag() {
        // Origin path 301s to a differently-named canonical path (same WireMock host).
        // The 301 response body is a decoy: it deserializes to a *different* (smaller) "largest"
        // tag than the canonical page, so a test that (incorrectly) parses the intermediate 3xx
        // body instead of following the redirect would report the WRONG version.
        wireMockServer.stubFor(get(urlPathEqualTo("/v2/library/redirect-basic/tags/list"))
                .withQueryParam("n", equalTo("100"))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location",
                                "/v2/library/redirect-basic/canonical/tags/list?n=100")
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "name": "library/redirect-basic",
                                  "tags": ["1.0.0"]
                                }
                                """)));

        wireMockServer.stubFor(get(urlPathEqualTo("/v2/library/redirect-basic/canonical/tags/list"))
                .withQueryParam("n", equalTo("100"))
                .willReturn(jsonResponse(200, """
                        {
                          "name": "library/redirect-basic",
                          "tags": ["3.5.0", "2.0.0", "1.0.0-alpine"]
                        }
                        """)));

        OciRegistryLatestSource latestSource =
                anonymousSource("http://localhost:8090/v2/library/redirect-basic");

        VersionValue result = latestSource.version();

        assertEquals("3.5.0", result.value(),
                "must follow the 301 to the canonical endpoint and select the largest tag found "
                        + "there — 1.0.0 (from the intermediate 3xx decoy body) must be ignored");

        wireMockServer.verify(getRequestedFor(
                        urlPathEqualTo("/v2/library/redirect-basic/canonical/tags/list"))
                .withQueryParam("n", equalTo("100")));
    }

    @Test
    void redirect_onFirstPage_composesWithLinkHeaderPagination() {
        // Page 1 is served via a redirect: origin 302s to a canonical page-1 endpoint whose
        // Link: rel="next" header is a RELATIVE reference ("list?n=2&last=1.1.0", no leading
        // slash). Per RFC 3986 this must be resolved against the canonical (post-redirect)
        // request URI — the base URI whose response the header came from — not against the
        // origin request URI:
        //   resolved against canonical -> /v2/.../redirect-paged/canonical/tags/list?n=2&last=1.1.0
        //   resolved against origin    -> /v2/.../redirect-paged/tags/list?n=2&last=1.1.0
        // Only the canonical page-2 path is stubbed (with an explicit last=1.1.0 match); the
        // origin path is stubbed ONLY for the no-"last" (page-1) request via withQueryParam("last",
        // absent()), so a request for the origin path WITH last=1.1.0 matches no stub and 404s.
        // If the source resolves the next-page URI against the origin request URI instead of the
        // canonical one (the bug), the page-2 fetch 404s and version() throws instead of returning
        // "2.0.0" — pinning the fix.
        wireMockServer.stubFor(get(urlPathEqualTo("/v2/library/redirect-paged/tags/list"))
                .withQueryParam("n", equalTo("2"))
                .withQueryParam("last", absent())
                .willReturn(aResponse()
                        .withStatus(302)
                        .withHeader("Location",
                                "/v2/library/redirect-paged/canonical/tags/list?n=2")));

        wireMockServer.stubFor(get(urlPathEqualTo("/v2/library/redirect-paged/canonical/tags/list"))
                .withQueryParam("n", equalTo("2"))
                .withQueryParam("last", absent())
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Link", "<list?n=2&last=1.1.0>; rel=\"next\"")
                        .withBody("""
                                {"name": "library/redirect-paged", "tags": ["1.0.0", "1.1.0"]}
                                """)));

        wireMockServer.stubFor(get(urlPathEqualTo("/v2/library/redirect-paged/canonical/tags/list"))
                .withQueryParam("n", equalTo("2"))
                .withQueryParam("last", equalTo("1.1.0"))
                .willReturn(jsonResponse(200, """
                        {"name": "library/redirect-paged", "tags": ["2.0.0"]}
                        """)));

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                "http://localhost:8090/v2/library/redirect-paged", Optional.empty(), Optional.empty(),
                new TagSelection(2, 100, Optional.empty(), false), SEMVER_PARSER);

        VersionValue result = latestSource.version();

        assertEquals("2.0.0", result.value(),
                "the redirected first page's relative Link 'next' href must resolve against the "
                        + "canonical (post-redirect) URI, not the origin request URI, so page 2 is "
                        + "fetched from the canonical endpoint and its largest tag is selected");

        wireMockServer.verify(getRequestedFor(
                        urlPathEqualTo("/v2/library/redirect-paged/canonical/tags/list"))
                .withQueryParam("n", equalTo("2"))
                .withQueryParam("last", equalTo("1.1.0")));

        wireMockServer.verify(0, getRequestedFor(
                        urlPathEqualTo("/v2/library/redirect-paged/tags/list"))
                .withQueryParam("last", equalTo("1.1.0")));
    }

    @Test
    void redirect_toUltimatelyNon2xx_surfacesAsSourceReadFailure() {
        // The 301 target itself fails (500) — must not be parsed as a tags page.
        wireMockServer.stubFor(get(urlPathEqualTo("/v2/library/redirect-fails/tags/list"))
                .withQueryParam("n", equalTo("100"))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location",
                                "/v2/library/redirect-fails/canonical/tags/list?n=100")));

        wireMockServer.stubFor(get(urlPathEqualTo("/v2/library/redirect-fails/canonical/tags/list"))
                .withQueryParam("n", equalTo("100"))
                .willReturn(aResponse().withStatus(500)));

        OciRegistryLatestSource latestSource =
                anonymousSource("http://localhost:8090/v2/library/redirect-fails");

        assertThrows(RuntimeException.class, latestSource::version,
                "a redirect chain ending in a non-2xx response must surface as a source-read "
                        + "failure, never as a parsed (empty/garbage) tags page");
    }

    @Test
    void redirect_isNotCachedBetweenCalls_originIsRequestedOnEveryCall() {
        wireMockServer.stubFor(get(urlPathEqualTo("/v2/library/redirect-nocache/tags/list"))
                .withQueryParam("n", equalTo("100"))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location",
                                "/v2/library/redirect-nocache/canonical/tags/list?n=100")));

        wireMockServer.stubFor(get(urlPathEqualTo("/v2/library/redirect-nocache/canonical/tags/list"))
                .withQueryParam("n", equalTo("100"))
                .willReturn(jsonResponse(200, """
                        {"name": "library/redirect-nocache", "tags": ["1.2.3"]}
                        """)));

        OciRegistryLatestSource latestSource =
                anonymousSource("http://localhost:8090/v2/library/redirect-nocache");

        latestSource.version();
        latestSource.version();

        wireMockServer.verify(2, getRequestedFor(
                urlPathEqualTo("/v2/library/redirect-nocache/tags/list")));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder jsonResponse(
            int status, String body) {
        return aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body);
    }

    private static OciRegistryLatestSource anonymousSource(String baseUrl) {
        return new OciRegistryLatestSource(baseUrl, Optional.empty(), Optional.empty(),
                new TagSelection(100, 1000, Optional.empty(), false), SEMVER_PARSER);
    }
}
