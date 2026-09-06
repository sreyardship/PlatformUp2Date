package org.yardship.integration.adapter;

import org.junit.jupiter.api.Test;
import org.yardship.confcheck.adapter.YamlAppConfigReader;
import org.yardship.confcheck.port.AppConfig;
import org.yardship.confcheck.port.AppConfigReader;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the real {@link YamlAppConfigReader} adapter, isolating the {@code
 * http-prometheus} current-source fields (ADR-0033, slice 04): {@code current.metric},
 * {@code current.version-label}, {@code current.labels}, and the shared {@code current.regex}.
 *
 * <p>A fresh fixture ({@code config/prometheus-app.yaml}) and a fresh test class, mirroring
 * {@code YamlAppConfigReaderHeaderSurfaceIT}'s precedent exactly: {@code mixed-apps.yaml} has no
 * {@code http-prometheus} app, and {@code ConfigFileValidationPrometheusSurfaceTests} builds
 * {@link AppConfig} by hand, so nothing else exercises the real reader against a real
 * {@code http-prometheus} entry.
 *
 * <p>The {@code labels:} assertion is the load-bearing one: slice 02's mandatory SmallRye binding
 * gate found that an underscore key ({@code pod_name}) is exactly the case at risk of being
 * mangled by a kebab-case naming strategy. {@code YamlAppConfigReader} is plain Jackson, not
 * SmallRye, but the same risk shape applies to any YAML-to-map binding path, so this test pins the
 * verbatim-keys requirement here too.
 */
class YamlAppConfigReaderPrometheusSurfaceIT {

    private Path fixture(String name) throws URISyntaxException {
        return Path.of(getClass().getResource("/config/" + name).toURI());
    }

    @Test
    void httpPrometheusApp_allFields_surviveTheYamlRoundTrip() throws URISyntaxException {
        AppConfigReader reader = new YamlAppConfigReader(fixture("prometheus-app.yaml"));

        List<AppConfig> apps = reader.apps();

        assertEquals(1, apps.size());
        AppConfig app = apps.get(0);
        assertEquals("blackbox-exporter", app.name());
        assertEquals("http-prometheus", app.currentType());
        assertEquals("http://localhost:9115/metrics", app.currentUrl().orElseThrow());
        assertEquals("blackbox_exporter_build_info", app.currentMetric().orElseThrow(),
                "current.metric must survive the YAML round trip into AppConfig");
        assertEquals("version", app.currentVersionLabel().orElseThrow(),
                "current.version-label must survive the YAML round trip into AppConfig");
        assertEquals("(\\d+\\.\\d+\\.\\d+)", app.currentRegex().orElseThrow(),
                "current.regex (shared with http-header) must survive the YAML round trip for an "
                        + "http-prometheus app too");
    }

    @Test
    void httpPrometheusApp_labels_roundTripAsAMapWithKeysVerbatim() throws URISyntaxException {
        AppConfigReader reader = new YamlAppConfigReader(fixture("prometheus-app.yaml"));

        AppConfig app = reader.apps().get(0);
        Map<String, String> labels = app.currentLabels();

        assertEquals(2, labels.size(), "both configured label entries must survive the round trip, got: " + labels);
        assertTrue(labels.containsKey("job"), "the 'job' key must bind verbatim, got keys: " + labels.keySet());
        assertTrue(labels.containsKey("pod_name"),
                "the underscore key 'pod_name' must survive VERBATIM, not mangled to 'pod-name' -- "
                        + "this is exactly the case slice 02's binding gate found at risk; got keys: "
                        + labels.keySet());
        assertEquals("blackbox", labels.get("job"));
        assertEquals("blackbox-0", labels.get("pod_name"));
    }

    @Test
    void httpPrometheusApp_withNoLabelsBlock_labelsIsAnEmptyMap() throws URISyntaxException {
        // mixed-apps.yaml has no http-prometheus app at all, so reuse header-app.yaml's http-header
        // entry (which also has no 'labels:' block) purely to prove the absent-block default is an
        // empty map, not null -- AppConfig#currentLabels() must never be null.
        AppConfigReader reader = new YamlAppConfigReader(fixture("header-app.yaml"));

        AppConfig app = reader.apps().get(0);

        assertTrue(app.currentLabels().isEmpty(),
                "currentLabels() must be an empty map (never null) when no 'labels:' block is configured");
    }
}
