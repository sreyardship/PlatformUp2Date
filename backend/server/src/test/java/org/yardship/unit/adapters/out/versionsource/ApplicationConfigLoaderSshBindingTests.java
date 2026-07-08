package org.yardship.unit.adapters.out.versionsource;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Binding regression test for the {@code ssh-os-release} config fields, driven through REAL SmallRye
 * {@code @ConfigMapping} resolution (not a hand-built {@code VersionSource} fake).
 *
 * <p>This guards a specific failure mode: the SSH getters were briefly declared as {@code default}
 * (bodied) interface methods, which SmallRye does NOT bind from configuration — so {@code host()},
 * {@code user()}, the key fields, etc. would silently stay empty and every {@code ssh-os-release} app
 * would fail boot. The rest of the SSH test corpus constructs {@code VersionSource} by hand and so
 * cannot catch a binding regression; this test loads the values from a config source and asserts they
 * are actually populated.
 */
class ApplicationConfigLoaderSshBindingTests {

    private static ApplicationConfigLoader bind(Map<String, String> props) {
        SmallRyeConfig config = new SmallRyeConfigBuilder()
                .withMapping(ApplicationConfigLoader.class)
                .withSources(new PropertiesConfigSource(props, "test-ssh", 100))
                .build();
        return config.getConfigMapping(ApplicationConfigLoader.class);
    }

    // Common required top-level config so the standalone mapping binds. Durations are given in
    // ISO-8601 (PT1H) because this standalone SmallRyeConfig does not register Quarkus's "1h"
    // shorthand Duration converter — the @WithDefault("1h") on the trigger windows would otherwise
    // fail to convert. (Production runs under Quarkus, which does register it.)
    private static Map<String, String> baseProps() {
        Map<String, String> props = new HashMap<>();
        props.put("platform-config.scrape-interval", "1h");
        props.put("platform-config.scrape-trigger.max-per-window", "10");
        props.put("platform-config.scrape-trigger.window", "PT1H");
        props.put("platform-config.targeted-scrape-trigger.max-per-window", "30");
        props.put("platform-config.targeted-scrape-trigger.window", "PT1H");
        return props;
    }

    @Test
    void sshOsReleaseFields_bindFromConfiguration() {
        Map<String, String> props = baseProps();
        props.put("platform-config.apps[0].name", "openwrt-router");
        props.put("platform-config.apps[0].version-scheme", "calver");
        props.put("platform-config.apps[0].calver-format", "YY.0M.MICRO");
        props.put("platform-config.apps[0].current.type", "ssh-os-release");
        props.put("platform-config.apps[0].current.host", "router.lan");
        props.put("platform-config.apps[0].current.port", "2222");
        props.put("platform-config.apps[0].current.user", "monitor");
        props.put("platform-config.apps[0].current.private-key-file", "/etc/keys/id_ed25519");
        props.put("platform-config.apps[0].current.host-key", "ssh-rsa AAAATESTKEY");
        props.put("platform-config.apps[0].current.release-field", "VERSION_ID");
        props.put("platform-config.apps[0].latest.type", "http-regex");
        props.put("platform-config.apps[0].latest.url", "https://downloads.openwrt.org/releases/");
        props.put("platform-config.apps[0].latest.regex", "href=\"(\\d+\\.\\d+(?:\\.\\d+)?)/\"");

        ApplicationConfigLoader.VersionSource current = bind(props).apps().getFirst().current();

        assertEquals("ssh-os-release", current.type());
        assertTrue(current.host().isPresent(), "host must bind from config");
        assertEquals("router.lan", current.host().get());
        assertEquals(2222, current.port().orElseThrow(), "port must bind from config");
        assertEquals("monitor", current.user().orElseThrow(), "user must bind from config");
        assertEquals("/etc/keys/id_ed25519", current.privateKeyFile().orElseThrow(),
                "private-key-file must bind from config");
        assertEquals("ssh-rsa AAAATESTKEY", current.hostKey().orElseThrow(),
                "host-key must bind from config");
        assertEquals("VERSION_ID", current.releaseField().orElseThrow(),
                "release-field must bind from config");
    }

    @Test
    void sshFields_areEmpty_whenAbsentFromConfiguration() {
        Map<String, String> props = baseProps();
        props.put("platform-config.apps[0].name", "plain-http-app");
        props.put("platform-config.apps[0].current.type", "http");
        props.put("platform-config.apps[0].current.url", "https://example.test/version");
        props.put("platform-config.apps[0].latest.type", "github-release");
        props.put("platform-config.apps[0].latest.repo", "owner/repo");

        ApplicationConfigLoader.VersionSource current = bind(props).apps().getFirst().current();

        assertTrue(current.host().isEmpty(), "host must default to empty for a non-ssh app");
        assertTrue(current.user().isEmpty());
        assertTrue(current.hostKey().isEmpty());
        assertTrue(current.privateKey().isEmpty());
        assertTrue(current.releaseField().isEmpty());
    }
}
