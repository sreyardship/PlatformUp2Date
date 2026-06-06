package org.yardship.integration.adapters.out.versionclient;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Points two configured apps (good-app, bad-app) at a standalone WireMock server
 * running on the fixed port 8089. The scheduler is disabled so scraping is only
 * triggered explicitly by the test.
 */
public class WireMockTestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.ofEntries(
                Map.entry("platform-config.scrape-interval", "1h"),
                Map.entry("quarkus.scheduler.enabled", "false"),
                Map.entry("platform-config.apps[0].name", "good-app"),
                Map.entry("platform-config.apps[0].current", "http://localhost:8089/good/current"),
                Map.entry("platform-config.apps[0].latest", "http://localhost:8089/good/latest"),
                Map.entry("platform-config.apps[1].name", "bad-app"),
                Map.entry("platform-config.apps[1].current", "http://localhost:8089/bad/current"),
                Map.entry("platform-config.apps[1].latest", "http://localhost:8089/bad/latest")
        );
    }
}
