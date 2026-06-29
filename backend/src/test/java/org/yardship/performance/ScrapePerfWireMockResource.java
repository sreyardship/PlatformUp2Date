package org.yardship.performance;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.util.HashMap;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * {@link QuarkusTestResourceLifecycleManager} for the performance harness.
 *
 * <p>Starts a WireMock server on a dynamic port and registers {@value MAX_N} monitored apps up
 * front (the largest sweep point). Each app gets:
 * <ul>
 *   <li>a {@code current} HTTP stub returning {@code {"version":"1.0.0"}} after a uniform fixed
 *       delay;</li>
 *   <li>a {@code latest} GitHub releases stub returning a single release array after the same
 *       delay.</li>
 * </ul>
 *
 * <p>App names follow the pattern {@code app-0 .. app-(MAX_N-1)}, repos {@code org/app-i},
 * current endpoints {@code /app-i/current}. The uniform fixed delay ({@value STUB_DELAY_MS} ms)
 * gives each network leg realistic latency without hitting a real network. The Quarkus config
 * overrides wire all {@value MAX_N} apps into the CDI container so that sub-listing is the only
 * knob the sweep harness needs to turn per measurement point.
 *
 * <p>The {@code github-release} latest source hardcodes its host to the real GitHub API
 * (ADR-0011), so the global {@code platform-config.github.api-base-url} override redirects it
 * to WireMock.
 *
 * <p>{@link #APP_COUNT} is kept as a convenience constant for the slice-01/02 single-point
 * measurements; it is simply the first few of the {@value MAX_N} registered apps.
 */
public class ScrapePerfWireMockResource implements QuarkusTestResourceLifecycleManager {

    /** Uniform fixed delay applied to every stub response (milliseconds). */
    public static final int STUB_DELAY_MS = 50;

    /**
     * Maximum number of apps registered in WireMock (the largest sweep point).
     * All {@value MAX_N} apps are wired into the CDI container via config overrides so no
     * per-N WireMock restart is required.
     */
    public static final int MAX_N = 50;

    /**
     * Number of apps used by the slice-01/02 single-point harness measurements.
     * Must be ≤ {@value MAX_N}.
     */
    public static final int APP_COUNT = 3;

    private WireMockServer wireMockServer;

    @Override
    public Map<String, String> start() {
        wireMockServer = new WireMockServer(options().dynamicPort());
        wireMockServer.start();

        String base = "http://localhost:" + wireMockServer.port();

        // Register MAX_N apps up front — the sweep harness sub-lists as needed.
        for (int i = 0; i < MAX_N; i++) {
            String name = "app-" + i;
            String repo = "org/app-" + i;

            // Current HTTP stub
            wireMockServer.stubFor(get(urlEqualTo("/" + name + "/current"))
                    .willReturn(delayedJson(STUB_DELAY_MS, "{\"version\":\"1.0.0\"}")));

            // Latest GitHub releases stub (github-release source calls <base>/repos/{repo}/releases)
            wireMockServer.stubFor(get(urlPathEqualTo("/repos/" + repo + "/releases"))
                    .willReturn(delayedJson(STUB_DELAY_MS,
                            "[{\"tag_name\":\"v1.1.0\",\"prerelease\":false,\"draft\":false}]")));
        }

        Map<String, String> overrides = new HashMap<>();

        // Disable the background scheduler so scraping is triggered only by the harness call
        overrides.put("quarkus.scheduler.enabled", "false");

        // Scrape interval is irrelevant here: the in-memory store starts empty, so a scrape always
        // runs on the first getApplications() call regardless of this value
        overrides.put("platform-config.scrape-interval", "1h");

        // Redirect the github-release latest source from api.github.com to WireMock
        overrides.put("platform-config.github.api-base-url", base);

        // Wire all MAX_N apps into the CDI container
        for (int i = 0; i < MAX_N; i++) {
            String name = "app-" + i;
            String prefix = "platform-config.apps[" + i + "]";
            overrides.put(prefix + ".name", name);
            overrides.put(prefix + ".current.type", "http");
            overrides.put(prefix + ".current.url", base + "/" + name + "/current");
            overrides.put(prefix + ".latest.type", "github-release");
            overrides.put(prefix + ".latest.repo", "org/" + name);
        }

        return overrides;
    }

    @Override
    public void stop() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    private static ResponseDefinitionBuilder delayedJson(int delayMs, String body) {
        return aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withFixedDelay(delayMs)
                .withBody(body);
    }
}
