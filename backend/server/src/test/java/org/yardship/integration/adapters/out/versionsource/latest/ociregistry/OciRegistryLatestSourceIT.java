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
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.adapters.out.versionsource.latest.ociregistry.OciRegistryLatestSource;
import org.yardship.core.domain.primitives.VersionValue;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * <p>This slice covers the fetch-and-select round-trip: single-page tags/list (no challenge),
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
                          "tags": ["7.0.0", "7.0.0-alpine", "99.0.0-rc1", "latest", "7.2.0", "edge"]
                        }
                        """)));

        OciRegistryLatestSource latestSource =
                anonymousSource("http://localhost:8090/v2/library/redis");

        VersionValue result = latestSource.version();

        assertEquals("7.2.0", result.value(),
                "7.2.0 is the largest clean semver; variant and non-semver tags are skipped");
    }

    @Test
    void read_followsRawTagsRedirect_andSelectsLargestEligibleTagFromCanonicalResponse() {
        String rawPath = "/v2/library/redis/tags/list";
        String canonicalPath = "/canonical/v2/library/redis/tags/list";
        wireMockServer.stubFor(get(urlPathEqualTo(rawPath))
                .withQueryParam("n", equalTo("100"))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", canonicalPath + "?n=100")));
        wireMockServer.stubFor(get(urlPathEqualTo(canonicalPath))
                .withQueryParam("n", equalTo("100"))
                .willReturn(jsonResponse(200, """
                        {
                          "name": "library/redis",
                          "tags": ["latest", "1.9.0", "2.0.0", "2.1.0-rc1"]
                        }
                        """)));

        OciRegistryLatestSource latestSource =
                anonymousSource("http://localhost:8090/v2/library/redis");

        VersionValue result = latestSource.version();

        assertEquals("2.0.0", result.value(),
                "a redirected anonymous tags response must still select the largest eligible tag");
        wireMockServer.verify(getRequestedFor(urlEqualTo(canonicalPath + "?n=100")));
    }

    @Test
    void read_doesNotCacheRedirectTarget_betweenVersionCalls_orAddRegistryConfigField() {
        String rawPath = "/v2/library/redis/tags/list";
        String firstCanonicalPath = "/canonical-one/v2/library/redis/tags/list";
        String secondCanonicalPath = "/canonical-two/v2/library/redis/tags/list";
        String scenario = "redirect-target-not-cached";

        wireMockServer.stubFor(get(urlEqualTo(rawPath + "?n=100"))
                .inScenario(scenario)
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", firstCanonicalPath + "?n=100"))
                .willSetStateTo("second-call"));
        wireMockServer.stubFor(get(urlEqualTo(rawPath + "?n=100"))
                .inScenario(scenario)
                .whenScenarioStateIs("second-call")
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", secondCanonicalPath + "?n=100")));
        wireMockServer.stubFor(get(urlEqualTo(firstCanonicalPath + "?n=100"))
                .willReturn(jsonResponse(200, """
                        {"name": "library/redis", "tags": ["1.0.0"]}
                        """)));
        wireMockServer.stubFor(get(urlEqualTo(secondCanonicalPath + "?n=100"))
                .willReturn(jsonResponse(200, """
                        {"name": "library/redis", "tags": ["2.0.0"]}
                        """)));

        OciRegistryLatestSource latestSource =
                anonymousSource("http://localhost:8090/v2/library/redis");

        VersionValue firstResult = latestSource.version();
        VersionValue secondResult = latestSource.version();

        assertEquals("1.0.0", firstResult.value(),
                "the first call must follow the first redirect target");
        assertEquals("2.0.0", secondResult.value(),
                "each call must resolve its own redirect rather than reuse a prior target");
        wireMockServer.verify(2, getRequestedFor(urlEqualTo(rawPath + "?n=100")));
        wireMockServer.verify(1, getRequestedFor(urlEqualTo(firstCanonicalPath + "?n=100")));
        wireMockServer.verify(1, getRequestedFor(urlEqualTo(secondCanonicalPath + "?n=100")));

        assertEquals(Set.of(
                        "type", "url", "caCert", "insecureSkipTlsVerify", "repo", "registry", "regex",
                        "namespace", "workload", "container", "versionKey", "stripPrerelease", "auth", "pageSize",
                        "host", "port", "user", "privateKey", "privateKeyFile", "hostKey", "knownHosts",
                        "releaseField", "maxTags", "prereleaseFilter"),
                Arrays.stream(ApplicationConfigLoader.VersionSource.class.getDeclaredMethods())
                        .map(method -> method.getName())
                        .collect(Collectors.toSet()),
                "redirect traversal must not add a registry configuration field");
    }

    @Test
    void read_mapsFinalNon2xxAfterRedirect_toSourceReadFailure() {
        String rawPath = "/v2/library/redis/tags/list";
        String canonicalPath = "/canonical/v2/library/redis/tags/list";
        wireMockServer.stubFor(get(urlEqualTo(rawPath + "?n=100"))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", canonicalPath + "?n=100")));
        wireMockServer.stubFor(get(urlEqualTo(canonicalPath + "?n=100"))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"name\":\"library/redis\",\"tags\":[\"99.0.0\"]}")));

        OciRegistryLatestSource latestSource =
                anonymousSource("http://localhost:8090/v2/library/redis");

        RuntimeException failure = assertThrows(RuntimeException.class, latestSource::version);

        assertTrue(failure.getMessage().contains("Unexpected HTTP 404"));
        wireMockServer.verify(1, getRequestedFor(urlEqualTo(rawPath + "?n=100")));
        wireMockServer.verify(1, getRequestedFor(urlEqualTo(canonicalPath + "?n=100")));
    }

    @Test
    void read_followsRedirectedFirstPage_intoPagination_andSelectsLargestEligibleTag() {
        String rawPath = "/v2/library/nginx/tags/list";
        String canonicalPath = "/canonical/v2/library/nginx/tags/list";
        wireMockServer.stubFor(get(urlEqualTo(rawPath + "?n=2"))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", canonicalPath + "?n=2")));
        wireMockServer.stubFor(get(urlEqualTo(canonicalPath + "?n=2"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Link",
                                "<" + rawPath + "?n=2&last=2.0.0>; rel=\"next\"")
                        .withBody("""
                                {
                                  "name": "library/nginx",
                                  "tags": ["1.0.0", "2.0.0"]
                                }
                                """)));
        wireMockServer.stubFor(get(urlEqualTo(rawPath + "?n=2&last=2.0.0"))
                .willReturn(jsonResponse(200, """
                        {
                          "name": "library/nginx",
                          "tags": ["3.0.0", "latest"]
                        }
                        """)));

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                "http://localhost:8090/v2/library/nginx", Optional.empty(), Optional.empty(),
                new TagSelection(2, 1000, Optional.empty(), false), SEMVER_PARSER);

        VersionValue result = latestSource.version();

        assertEquals("3.0.0", result.value(),
                "pagination must continue from the redirected first page and select the global largest tag");
        wireMockServer.verify(getRequestedFor(urlEqualTo(canonicalPath + "?n=2")));
        wireMockServer.verify(getRequestedFor(urlEqualTo(rawPath + "?n=2&last=2.0.0")));
    }

    @Test
    void read_followsRedirectedAnonymousPaginationPage_andSelectsLargestEligibleTag() {
        String tagsPath = "/v2/library/nginx/tags/list";
        String canonicalPath = "/canonical/v2/library/nginx/tags/list";
        wireMockServer.stubFor(get(urlEqualTo(tagsPath + "?n=2"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Link",
                                "<" + tagsPath + "?n=2&last=2.0.0>; rel=\"next\"")
                        .withBody("""
                                {
                                  "name": "library/nginx",
                                  "tags": ["1.0.0", "2.0.0"]
                                }
                                """)));
        wireMockServer.stubFor(get(urlEqualTo(tagsPath + "?n=2&last=2.0.0"))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", canonicalPath + "?n=2&last=2.0.0")));
        wireMockServer.stubFor(get(urlEqualTo(canonicalPath + "?n=2&last=2.0.0"))
                .willReturn(jsonResponse(200, """
                        {
                          "name": "library/nginx",
                          "tags": ["3.0.0", "latest"]
                        }
                        """)));

        OciRegistryLatestSource latestSource = new OciRegistryLatestSource(
                "http://localhost:8090/v2/library/nginx", Optional.empty(), Optional.empty(),
                new TagSelection(2, 1000, Optional.empty(), false), SEMVER_PARSER);

        VersionValue result = latestSource.version();

        assertEquals("3.0.0", result.value(),
                "an anonymous pagination request redirected to the canonical endpoint must still contribute to selection");
        wireMockServer.verify(getRequestedFor(urlEqualTo(tagsPath + "?n=2&last=2.0.0")));
        wireMockServer.verify(1, getRequestedFor(urlEqualTo(canonicalPath + "?n=2&last=2.0.0")));
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
