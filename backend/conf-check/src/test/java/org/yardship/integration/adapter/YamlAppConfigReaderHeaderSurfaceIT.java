package org.yardship.integration.adapter;

import org.junit.jupiter.api.Test;
import org.yardship.confcheck.adapter.YamlAppConfigReader;
import org.yardship.confcheck.port.AppConfig;
import org.yardship.confcheck.port.AppConfigReader;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration test for the real {@link YamlAppConfigReader} adapter, isolating the ONE path
 * {@code YamlAppConfigReaderIT}'s {@code mixed-apps.yaml} fixture cannot exercise: an
 * {@code http-header} app's {@code current.version-header} and {@code current.regex} fields.
 *
 * <p>Deliberately a separate fixture ({@code config/header-app.yaml}) and a separate test class
 * rather than adding an app to {@code mixed-apps.yaml}: that fixture is asserted against by both
 * {@code YamlAppConfigReaderIT} (which pins {@code assertEquals(3, apps.size())}) and
 * {@code ConfigCommandWiringTests} (which asserts specific per-app output), and this module's
 * "no existing conf-check test is modified" constraint (see
 * {@code .scratch/http-header-current-source/issues/04-conf-check-header-surface.md}) means adding
 * a fourth app there would require touching both of those pre-existing, unrelated tests. A fresh
 * fixture + fresh test class disturbs neither.
 *
 * <p>This is exactly the seam the slice-04 review flagged as missing: {@code YamlAppConfigReaderIT}
 * and {@code ConfigCommandWiringTests} both run only against {@code mixed-apps.yaml}, which has no
 * {@code http-header} app, and {@code ConfigFileValidationHeaderSurfaceTests} builds {@link AppConfig}
 * by hand — so nothing exercises the real reader against a real {@code http-header} entry.
 */
class YamlAppConfigReaderHeaderSurfaceIT {

    private Path fixture(String name) throws URISyntaxException {
        return Path.of(getClass().getResource("/config/" + name).toURI());
    }

    @Test
    void httpHeaderApp_versionHeaderAndRegex_bothSurviveTheYamlRoundTrip() throws URISyntaxException {
        AppConfigReader reader = new YamlAppConfigReader(fixture("header-app.yaml"));

        List<AppConfig> apps = reader.apps();

        assertEquals(1, apps.size());
        AppConfig jenkins = apps.get(0);
        assertEquals("jenkins", jenkins.name());
        assertEquals("http-header", jenkins.currentType());
        assertEquals("http://localhost:8097/", jenkins.currentUrl().orElseThrow());
        assertEquals("X-Jenkins", jenkins.currentHeaderName().orElseThrow(),
                "current.version-header must survive the YAML round trip into AppConfig — this is "
                        + "the field the review found YamlAppConfigReader silently drops");
        assertEquals("(\\d+\\.\\d+\\.\\d+)", jenkins.currentHeaderRegex().orElseThrow(),
                "current.regex (optional, for http-header) must survive the YAML round trip too");
    }
}
