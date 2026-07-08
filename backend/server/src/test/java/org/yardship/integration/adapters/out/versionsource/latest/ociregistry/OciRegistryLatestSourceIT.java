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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
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
