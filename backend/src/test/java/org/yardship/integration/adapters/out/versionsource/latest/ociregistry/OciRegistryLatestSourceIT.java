package org.yardship.integration.adapters.out.versionsource.latest.ociregistry;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.latest.ociregistry.OciRegistryLatestSourceFactory;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import org.yardship.core.ports.out.LatestVersionSource;
import org.yardship.integration.adapters.out.versionsource.latest.LatestSourceFragment;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the real {@code oci-registry} latest-version kind, built end to end through
 * {@link OciRegistryLatestSourceFactory#create} against a standalone WireMock server on port 8090
 * standing in for a registry that answers {@code tags/list} directly (no bearer challenge). Plain
 * JUnit — the kind needs no CDI.
 *
 * <p>Intentionally thin: redirects, TLS and the connection-failure retry are proven once for the
 * shared transport in {@code HttpTransportIT}; tag ranking, filtering and stripping by
 * {@code OciTagSelectorTests}. This class proves the fetch-and-select round trip: the URL the
 * factory assembles from {@code registry} and {@code repo}, a real {@code tags/list} body
 * deserialised, the all-skipped failure, and an unexpected probe status failing the read. The
 * bearer dance, pagination and the prerelease filter each have their own suite.
 */
class OciRegistryLatestSourceIT {

    private static final VersionParser SEMVER_PARSER = new VersionParser(VersionScheme.SEMVER);
    private static final String REGISTRY = "http://localhost:8090";

    static WireMockServer wireMockServer;

    private final OciRegistryLatestSourceFactory factory = new OciRegistryLatestSourceFactory();

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
    void version_skipsNonSemverTags_andPrereleaseVariantTags_endToEndThroughTheFactory() {
        wireMockServer.stubFor(get(urlPathEqualTo("/v2/library/redis/tags/list"))
                .willReturn(jsonResponse(200, """
                        {
                          "name": "library/redis",
                          "tags": ["7.0.0", "7.0.0-alpine", "7.2.0-rc1", "latest", "7.2.0", "edge"]
                        }
                        """)));

        LatestVersionSource source = factory.create(fragment("library/redis"), SEMVER_PARSER);

        assertEquals("7.2.0", source.version().value(),
                "7.2.0 is the largest clean semver; variant and non-semver tags are skipped");
    }

    @Test
    void version_throws_whenAllTagsAreSkipped() {
        wireMockServer.stubFor(get(urlPathEqualTo("/v2/library/scratch/tags/list"))
                .willReturn(jsonResponse(200, """
                        {
                          "name": "library/scratch",
                          "tags": ["latest", "edge", "1.0.0-alpine"]
                        }
                        """)));

        LatestVersionSource source = factory.create(fragment("library/scratch"), SEMVER_PARSER);

        assertThrows(RuntimeException.class, source::version,
                "an all-skipped tag set must surface as a per-app scrape failure, not a silent return");
    }

    @Test
    void version_requestsTheV2TagsListPath_theFactoryAssemblesFromRegistryAndRepo() {
        wireMockServer.stubFor(get(urlPathEqualTo("/v2/library/nginx/tags/list"))
                .willReturn(jsonResponse(200, """
                        {"name": "library/nginx", "tags": ["1.0.0"]}
                        """)));

        factory.create(fragment("library/nginx"), SEMVER_PARSER).version();

        wireMockServer.verify(getRequestedFor(urlPathEqualTo("/v2/library/nginx/tags/list")));
    }

    @Test
    void version_throws_whenTheProbeIsNeither200Nor401_namingTheStatus() {
        // Only 200 (read the page) and 401 (answer the challenge) mean anything to this kind; a 500
        // or a 404 is a failed read, not a tags page and not a challenge.
        wireMockServer.stubFor(get(urlPathEqualTo("/v2/library/broken/tags/list"))
                .willReturn(aResponse().withStatus(500).withBody("registry down")));

        LatestVersionSource source = factory.create(fragment("library/broken"), SEMVER_PARSER);

        IllegalStateException ex = assertThrows(IllegalStateException.class, source::version);
        assertTrue(ex.getMessage().contains("500"), "must name the status; was: " + ex.getMessage());
    }

    private static LatestSourceFragment fragment(String repo) {
        return LatestSourceFragment.ofType("oci-registry").registry(REGISTRY).repo(repo);
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder jsonResponse(
            int status, String body) {
        return aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body);
    }
}
