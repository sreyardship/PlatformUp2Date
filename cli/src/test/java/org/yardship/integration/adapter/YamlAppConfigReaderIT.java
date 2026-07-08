package org.yardship.integration.adapter;

import org.junit.jupiter.api.Test;
import org.yardship.cli.adapter.YamlAppConfigReader;
import org.yardship.cli.port.AppConfig;
import org.yardship.cli.port.AppConfigReader;
import org.yardship.core.domain.primitives.VersionScheme;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the real {@link YamlAppConfigReader} adapter: real filesystem I/O and real
 * Jackson-YAML parsing against fixture files under {@code cli/src/test/resources/config/}, no mocks
 * — mirrors {@code OfflineBodySourceIT}'s precedent for "real I/O belongs in integration/", since a
 * unit test with a fake body/parser can't prove the YAML-to-{@link AppConfig} mapping is correct.
 */
class YamlAppConfigReaderIT {

    private Path fixture(String name) throws URISyntaxException {
        return Path.of(getClass().getResource("/config/" + name).toURI());
    }

    @Test
    void mixedApps_everyAppFieldParsesCorrectly() throws URISyntaxException {
        AppConfigReader reader = new YamlAppConfigReader(fixture("mixed-apps.yaml"));

        List<AppConfig> apps = reader.apps();

        assertEquals(3, apps.size(), "must report every app, in file order");

        AppConfig chaosSocial = apps.get(0);
        assertEquals("chaos-social", chaosSocial.name());
        assertEquals(VersionScheme.SEMVER, chaosSocial.versionScheme(),
                "version-scheme omitted in YAML must default to SEMVER");
        assertTrue(chaosSocial.calverFormat().isEmpty());
        assertEquals("https://github.com/mastodon/mastodon/releases/tag/v{version}",
                chaosSocial.changelogUrl().orElseThrow());
        assertEquals("http", chaosSocial.currentType());
        assertEquals("https://chaos.social/api/v1/instance", chaosSocial.currentUrl().orElseThrow());
        assertEquals("/version", chaosSocial.currentVersionKey().orElseThrow(),
                "an explicitly configured version-key must be reported verbatim");
        assertFalse(chaosSocial.currentStripPrerelease(), "strip-prerelease omitted must default to false");
        assertEquals("http-regex", chaosSocial.latestType());
        assertEquals("https://example.test/releases", chaosSocial.latestUrl().orElseThrow());
        assertEquals("v(\\d+\\.\\d+\\.\\d+)", chaosSocial.latestRegex().orElseThrow());

        AppConfig openwrtRouter = apps.get(1);
        assertEquals("openwrt-router", openwrtRouter.name());
        assertEquals(VersionScheme.CALVER, openwrtRouter.versionScheme());
        assertEquals("YY.0M.MICRO", openwrtRouter.calverFormat().orElseThrow());
        assertEquals("https://openwrt.org/releases/{YY}.{0M}/notes-{version}",
                openwrtRouter.changelogUrl().orElseThrow());
        assertEquals("http", openwrtRouter.currentType());
        assertEquals("https://downloads.example.test/current-version.json", openwrtRouter.currentUrl().orElseThrow());
        assertTrue(openwrtRouter.currentVersionKey().isEmpty(),
                "version-key omitted in YAML must stay genuinely absent, not defaulted by the reader "
                        + "(see AppConfig#currentVersionKey()'s design note)");
        assertEquals("http-regex", openwrtRouter.latestType());
        assertEquals("href=\"(\\d+\\.\\d+(?:\\.\\d+)?)/\"", openwrtRouter.latestRegex().orElseThrow());

        AppConfig giteaOci = apps.get(2);
        assertEquals("gitea-oci", giteaOci.name());
        assertEquals(VersionScheme.SEMVER, giteaOci.versionScheme());
        assertTrue(giteaOci.changelogUrl().isEmpty(), "no changelog-url configured for this app");
        assertEquals("ssh-os-release", giteaOci.currentType());
        assertTrue(giteaOci.currentUrl().isEmpty());
        assertEquals("github-release", giteaOci.latestType());
        assertTrue(giteaOci.latestUrl().isEmpty());
        assertTrue(giteaOci.latestRegex().isEmpty());
    }

    @Test
    void versionScheme_omitted_defaultsToSemver() throws URISyntaxException {
        AppConfigReader reader = new YamlAppConfigReader(fixture("mixed-apps.yaml"));

        assertEquals(VersionScheme.SEMVER, reader.apps().get(0).versionScheme());
    }

    @Test
    void malformedYaml_throwsConfigReadException() throws URISyntaxException {
        AppConfigReader reader = new YamlAppConfigReader(fixture("malformed.yaml"));

        assertThrows(AppConfigReader.ConfigReadException.class, reader::apps,
                "invalid YAML syntax must be reported as a ConfigReadException, not a raw parser exception");
    }

    @Test
    void missingRequiredField_throwsConfigReadExceptionNamingTheField() throws URISyntaxException {
        AppConfigReader reader = new YamlAppConfigReader(fixture("missing-required-field.yaml"));

        AppConfigReader.ConfigReadException exception =
                assertThrows(AppConfigReader.ConfigReadException.class, reader::apps);
        assertTrue(exception.getMessage().toLowerCase().contains("name"),
                "message should clearly name the missing 'name' field; was: " + exception.getMessage());
    }

    @Test
    void unreadableFile_throwsConfigReadException() {
        AppConfigReader reader = new YamlAppConfigReader(Path.of("/no/such/platform-config-xyz.yaml"));

        assertThrows(AppConfigReader.ConfigReadException.class, reader::apps);
    }
}
