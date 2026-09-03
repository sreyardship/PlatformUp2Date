package org.yardship.unit.adapters.out.versionsource;

import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.adapters.out.versionsource.ChangelogTemplates;
import org.yardship.core.domain.primitives.ChangelogTemplate;
import org.yardship.core.domain.primitives.SemverVersion;
import org.yardship.core.domain.primitives.VersionScheme;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the eager, per-app {@link ChangelogTemplate} lookup (ADR-0021). Verifies that
 * construction failures include the app name while preserving fail-fast startup behavior.
 *
 * <p>Driven entirely through the package-visible {@code List<AppConfig>} constructor — "visible
 * for testing: lets tests drive this bean with plain fakes and no CDI container" — with fake
 * {@link ApplicationConfigLoader.AppConfig} implementations following the same anonymous-class
 * pattern as {@code VersionSourceResolverTests}.
 */
class ChangelogTemplatesTests {

    @Test
    void wrapsTheIllegalPlaceholderFailure_withTheOffendingAppsName() {
        // "{major}" is a semver-component token, illegal on a CALVER app.
        ApplicationConfigLoader.AppConfig app = app("my-app", VersionScheme.CALVER, "YY.0M.MICRO",
                Optional.of("https://example.com/changelog/{major}"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new ChangelogTemplates(List.of(app)));

        assertTrue(ex.getMessage().contains("my-app"),
                "the wrapped error must name the offending app; was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("major"),
                "the wrapped error must name the offending token; was: " + ex.getMessage());
    }

    @Test
    void resolvesALegalTemplate_forApp() {
        ApplicationConfigLoader.AppConfig app = app("alpha", VersionScheme.SEMVER, null,
                Optional.of("https://example.com/changelog/v{major}.{minor}.{patch}"));

        ChangelogTemplates templates = new ChangelogTemplates(List.of(app));

        Optional<ChangelogTemplate> template = templates.forApp("alpha");
        assertTrue(template.isPresent(), "a legal template resolves to a present optional");
        assertEquals("https://example.com/changelog/v3.10.5",
                template.get().resolve(new SemverVersion("3.10.5")));
    }

    @Test
    void unconfiguredChangelogUrl_yieldsAnAbsentTemplate_forApp() {
        ApplicationConfigLoader.AppConfig app = app("beta", VersionScheme.SEMVER, null, Optional.empty());

        ChangelogTemplates templates = new ChangelogTemplates(List.of(app));

        assertTrue(templates.forApp("beta").isEmpty());
    }

    // --- issue 02 / ADR-0032: an unnamed app must not break construction for its siblings -----

    @Test
    void unnamedApp_isSkipped_ratherThanBreakingConstructionOrItsNamedSibling() {
        // The unnamed app carries a changelog-url so the skip is actually exercised, not
        // vacuously true because there was nothing to build a template from.
        ApplicationConfigLoader.AppConfig unnamed = namedOrUnnamed(Optional.empty(),
                VersionScheme.SEMVER, null, Optional.of("https://example.com/changelog/v{major}"));
        ApplicationConfigLoader.AppConfig named = app("alpha", VersionScheme.SEMVER, null,
                Optional.of("https://example.com/changelog/v{major}.{minor}.{patch}"));

        // Must not throw: Map.copyOf/put on a null key would NPE if the unnamed app were not
        // skipped before reaching the map (the exact boot failure this slice exists to eliminate).
        ChangelogTemplates templates = new ChangelogTemplates(List.of(unnamed, named));

        assertTrue(templates.forApp("alpha").isPresent(), "the named sibling's entry must be intact");
        assertEquals("https://example.com/changelog/v3.10.5",
                templates.forApp("alpha").get().resolve(new SemverVersion("3.10.5")));
    }

    // --- fakes --------------------------------------------------------------------------------

    private static ApplicationConfigLoader.AppConfig app(
            String name, VersionScheme versionScheme, String calverFormat, Optional<String> changelogUrl) {
        return namedOrUnnamed(Optional.of(name), versionScheme, calverFormat, changelogUrl);
    }

    private static ApplicationConfigLoader.AppConfig namedOrUnnamed(
            Optional<String> name, VersionScheme versionScheme, String calverFormat,
            Optional<String> changelogUrl) {
        return new ApplicationConfigLoader.AppConfig() {
            @Override
            public Optional<String> name() {
                return name;
            }

            @Override
            public ApplicationConfigLoader.VersionSource current() {
                return null;
            }

            @Override
            public ApplicationConfigLoader.VersionSource latest() {
                return null;
            }

            @Override
            public VersionScheme versionScheme() {
                return versionScheme;
            }

            @Override
            public Optional<String> calverFormat() {
                return Optional.ofNullable(calverFormat);
            }

            @Override
            public Optional<String> changelogUrl() {
                return changelogUrl;
            }
        };
    }
}
