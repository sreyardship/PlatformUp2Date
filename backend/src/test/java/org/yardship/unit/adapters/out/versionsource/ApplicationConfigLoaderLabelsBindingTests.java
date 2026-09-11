package org.yardship.unit.adapters.out.versionsource;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE BINDING GATE for issue 02 (http-prometheus's {@code labels:} installation selector).
 *
 * <p>{@code labels:} is the first non-scalar field in {@code VersionSource} — every existing field
 * is a scalar {@code Optional} or the nested {@code Auth} interface. ADR-0032 rests on the config
 * document binding as a whole, so this proves the binding BEFORE any selector logic exists.
 *
 * <p>This test was run before any other test in this slice was written, per issue 02's binding
 * gate. Its FIRST candidate signature, {@code Optional<Map<String,String>>}, does NOT bind:
 * SmallRye's {@code ConfigMappingInterface} throws {@code IllegalArgumentException("Property type
 * ... cannot be optional")} at mapping-load time, before any YAML is even read — a load-time
 * failure, not a value-level one, so a hand-built {@code VersionSource} fake could never have
 * caught it (it doesn't go through {@code @ConfigMapping} at all). This is the issue's decided
 * response (1): the plain {@code Map<String,String>} type is used instead, defaulting to an empty
 * map when {@code labels:} is absent. {@link ApplicationConfigLoader.VersionSource#labels()}
 * therefore returns {@code Map<String,String>}, not {@code Optional<Map<String,String>>}.
 *
 * <p>The failure mode being guarded on the CHOSEN shape is silent, not a boot break: arbitrary map
 * keys cannot be structurally invalid, so a mangled or empty map would bind cleanly and quietly
 * select the wrong installation (or none) under a green board.
 *
 * <p>Driven through a REAL standalone {@link SmallRyeConfig}, exactly like
 * {@code ApplicationConfigLoaderSshBindingTests} — not a hand-built {@code VersionSource} fake,
 * which cannot exercise {@code @ConfigMapping} binding at all.
 */
class ApplicationConfigLoaderLabelsBindingTests {

    private static ApplicationConfigLoader bind(Map<String, String> props) {
        SmallRyeConfig config = new SmallRyeConfigBuilder()
                .withMapping(ApplicationConfigLoader.class)
                .withSources(new PropertiesConfigSource(props, "test-labels", 100))
                .build();
        return config.getConfigMapping(ApplicationConfigLoader.class);
    }

    // Common required top-level config so the standalone mapping binds (mirrors
    // ApplicationConfigLoaderSshBindingTests.baseProps()).
    private static Map<String, String> baseProps() {
        Map<String, String> props = new HashMap<>();
        props.put("platform-config.scrape-interval", "1h");
        props.put("platform-config.scrape-trigger.max-per-window", "10");
        props.put("platform-config.scrape-trigger.window", "PT1H");
        props.put("platform-config.targeted-scrape-trigger.max-per-window", "30");
        props.put("platform-config.targeted-scrape-trigger.window", "PT1H");
        return props;
    }

    /**
     * THE GATE. A {@code labels:} block with an underscore key ({@code pod_name}) round-trips as a
     * two-entry map with BOTH keys verbatim and values intact.
     *
     * <p>The underscore key is not decoration: Prometheus label names are
     * {@code [a-zA-Z_][a-zA-Z0-9_]*} and underscores are everywhere ({@code pod_name},
     * {@code kubernetes_namespace}), while {@code @ConfigMapping} applies kebab-case conversion to
     * METHOD names — a real risk of the map's KEYS being mangled the same way if the wrong
     * naming-strategy setting reaches map entries. Assert exact keys and non-emptiness: an empty or
     * mangled map is a silent failure, not a thrown one.
     */
    @Test
    void labels_roundTripAsATwoEntryMap_withBothKeysVerbatimAndValuesIntact() {
        Map<String, String> props = baseProps();
        props.put("platform-config.apps[0].name", "blackbox-a");
        props.put("platform-config.apps[0].current.type", "http-prometheus");
        props.put("platform-config.apps[0].current.url", "https://prom.example.test/federate");
        props.put("platform-config.apps[0].current.metric", "blackbox_exporter_build_info");
        props.put("platform-config.apps[0].current.labels.job", "blackbox");
        props.put("platform-config.apps[0].current.labels.pod_name", "blackbox-0");
        props.put("platform-config.apps[0].latest.type", "github-release");
        props.put("platform-config.apps[0].latest.repo", "prometheus/blackbox_exporter");

        ApplicationConfigLoader.VersionSource current = bind(props).apps().getFirst().current();

        Map<String, String> labels = current.labels();

        assertFalse(labels.isEmpty(), "labels() must be non-empty when a 'labels:' block is configured");
        assertEquals(2, labels.size(), "both configured label entries must survive binding, got: " + labels);
        assertTrue(labels.containsKey("job"), "the 'job' key must bind verbatim, got keys: " + labels.keySet());
        assertTrue(labels.containsKey("pod_name"),
                "the underscore key 'pod_name' must bind VERBATIM, not mangled to 'pod-name' by "
                        + "@ConfigMapping's kebab-case method-name conversion; got keys: " + labels.keySet());
        assertEquals("blackbox", labels.get("job"));
        assertEquals("blackbox-0", labels.get("pod_name"));
    }

    @Test
    void labels_areAbsent_whenNoLabelsBlockIsConfigured() {
        Map<String, String> props = baseProps();
        props.put("platform-config.apps[0].name", "no-selector-app");
        props.put("platform-config.apps[0].current.type", "http-prometheus");
        props.put("platform-config.apps[0].current.url", "https://prom.example.test/metrics");
        props.put("platform-config.apps[0].current.metric", "some_build_info");
        props.put("platform-config.apps[0].latest.type", "github-release");
        props.put("platform-config.apps[0].latest.repo", "example/some");

        ApplicationConfigLoader.VersionSource current = bind(props).apps().getFirst().current();

        assertTrue(current.labels().isEmpty(),
                "labels() must be an empty map when no 'labels:' block is configured");
    }
}
