package org.yardship.integration.adapters.out.versionclient;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Token-bearing sibling of {@link WireMockTestProfile}. Configures a single app whose
 * {@code current} and {@code latest} endpoints both hit the standalone WireMock server on
 * port 8089, and ALSO sets {@code platform-config.github.token=test-token} so the scrape's
 * {@code latest} leg authenticates against GitHub. The scheduler is disabled so scraping is
 * only triggered explicitly by the test.
 */
public class GithubAuthTestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.ofEntries(
                Map.entry("platform-config.scrape-interval", "1h"),
                Map.entry("quarkus.scheduler.enabled", "false"),
                Map.entry("platform-config.github.token", "test-token"),
                Map.entry("platform-config.apps[0].name", "good-app"),
                Map.entry("platform-config.apps[0].current", "http://localhost:8089/good/current"),
                Map.entry("platform-config.apps[0].latest", "http://localhost:8089/good/latest")
        );
    }
}
