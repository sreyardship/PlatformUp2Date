package org.yardship.integration.adapters.out.versionsource;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.yardship.core.ports.out.VersionSources;

import java.util.HashMap;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Exercises the documented opt-in entries through the ordinary configured sources. */
@QuarkusTest
@QuarkusTestResource(value = BuildVersionMonitoringIT.Endpoints.class, restrictToAnnotatedClass = true)
class BuildVersionMonitoringIT {
    @Inject
    VersionSources sources;

    @Test
    void components_haveIndependentCurrentObservations_andShareTheStableReleaseRepository() {
        var applications = sources.applicationSources().stream()
                .filter(app -> app.name().startsWith("platformup2date-"))
                .toList();
        assertEquals(2, applications.size());
        var backend = applications.stream().filter(app -> app.name().endsWith("backend")).findFirst().orElseThrow();
        var frontend = applications.stream().filter(app -> app.name().endsWith("frontend")).findFirst().orElseThrow();
        assertEquals("1.2.3", backend.current().version().value());
        assertEquals("1.2.4", frontend.current().version().value());
        assertEquals("1.3.0", backend.latest().version().value());
        assertEquals("1.3.0", frontend.latest().version().value());
    }

    public static class Endpoints implements QuarkusTestResourceLifecycleManager {
        private WireMockServer server;

        @Override
        public Map<String, String> start() {
            server = new WireMockServer(options().dynamicPort());
            server.start();
            server.stubFor(get(urlPathEqualTo("/api/version")).willReturn(aResponse()
                    .withHeader("Content-Type", "application/json").withBody("{\"version\":\"1.2.3\"}")));
            server.stubFor(get(urlPathEqualTo("/version.json")).willReturn(aResponse()
                    .withHeader("Content-Type", "application/json").withBody("{\"version\":\"1.2.4\"}")));
            server.stubFor(get(urlPathEqualTo("/repos/sreyardship/PlatformUp2Date/releases"))
                    .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                            [{"tag_name":"v1.2.5","draft":false,"prerelease":false},
                             {"tag_name":"v1.3.0","draft":false,"prerelease":false},
                             {"tag_name":"v2.0.0-rc.6","draft":false,"prerelease":true},
                             {"tag_name":"v3.0.0","draft":true,"prerelease":false}]
                            """)));
            Map<String, String> config = new HashMap<>();
            config.put("platform-config.github.api-base-url", server.baseUrl());
            for (int i = 0; i < 2; i++) {
                String prefix = "platform-config.apps[" + i + "].";
                config.put(prefix + "name", "platformup2date-" + (i == 0 ? "backend" : "frontend"));
                config.put(prefix + "version-scheme", "semver");
                config.put(prefix + "current.type", "http-json");
                config.put(prefix + "current.url", server.baseUrl() + (i == 0 ? "/api/version" : "/version.json"));
                config.put(prefix + "latest.type", "github-release");
                config.put(prefix + "latest.repo", "sreyardship/PlatformUp2Date");
            }
            return config;
        }

        @Override
        public void stop() {
            if (server != null) server.stop();
        }
    }
}
