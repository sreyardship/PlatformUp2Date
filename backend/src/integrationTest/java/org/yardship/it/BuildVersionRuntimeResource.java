package org.yardship.it;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.util.Map;

/** No reachable version sources; runtime identity overrides must be ignored. */
public class BuildVersionRuntimeResource implements QuarkusTestResourceLifecycleManager {
    @Override
    public Map<String, String> start() {
        return Map.ofEntries(
                Map.entry("quarkus.application.version", "9.9.9"),
                Map.entry("build.version", "9.9.9"),
                Map.entry("platform-config.scrape-interval", "1h"),
                Map.entry("platform-config.apps[0].name", "unreachable-fixture"),
                Map.entry("platform-config.apps[0].current.type", "http-json"),
                Map.entry("platform-config.apps[0].current.url", "http://127.0.0.1:1/version"),
                Map.entry("platform-config.apps[0].latest.type", "github-release"),
                Map.entry("platform-config.apps[0].latest.repo", "sreyardship/PlatformUp2Date"));
    }

    @Override
    public void stop() {}
}
