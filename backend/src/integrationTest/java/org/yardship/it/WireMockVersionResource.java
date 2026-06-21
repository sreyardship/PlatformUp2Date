package org.yardship.it;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Starts a WireMock server with one healthy app endpoint pair and points the
 * launched application's {@code platform-config} at it (via config overrides
 * passed to the application process). A short scrape interval makes the first
 * scrape happen within a second or two of startup.
 */
public class WireMockVersionResource implements QuarkusTestResourceLifecycleManager {

    private static final int PORT = 8090;
    private WireMockServer wireMockServer;

    @Override
    public Map<String, String> start() {
        wireMockServer = new WireMockServer(options().port(PORT));
        wireMockServer.start();
        wireMockServer.stubFor(get(urlEqualTo("/good/current"))
                .willReturn(json("{\"version\":\"1.0.0\"}")));
        // The github-release source lists releases at <base>/releases and picks the largest
        // semver tag, so the latest leg is served as a Releases array (query carries per_page).
        wireMockServer.stubFor(get(urlPathEqualTo("/good/latest/releases"))
                .willReturn(json("[{\"tag_name\":\"v2.0.0\",\"prerelease\":false,\"draft\":false},"
                        + "{\"tag_name\":\"v1.5.0\",\"prerelease\":false,\"draft\":false}]")));

        return Map.of(
                "platform-config.scrape-interval", "1s",
                "platform-config.apps[0].name", "good-app",
                "platform-config.apps[0].current.type", "http",
                "platform-config.apps[0].current.url", "http://localhost:" + PORT + "/good/current",
                "platform-config.apps[0].latest.type", "github-release",
                "platform-config.apps[0].latest.url", "http://localhost:" + PORT + "/good/latest");
    }

    @Override
    public void stop() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    private static ResponseDefinitionBuilder json(String body) {
        return aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(body);
    }
}
