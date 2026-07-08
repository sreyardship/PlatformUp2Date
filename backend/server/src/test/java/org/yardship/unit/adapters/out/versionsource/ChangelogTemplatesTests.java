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
 * Unit tests for {@link ChangelogTemplates} — the eager, per-app {@link ChangelogTemplate}
 * lookup (ADR-0021, issue 01). Closes a coverage gap: nothing previously exercised this bean's
 * app-name-wrapping of {@link ChangelogTemplate}'s own (token-only) construction failure, despite
 * "boot fails with an app-and-token-naming error for an illegal template" being an acceptance
 * criterion for issue 01.
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

    // --- fakes --------------------------------------------------------------------------------

    private static ApplicationConfigLoader.AppConfig app(
            String name, VersionScheme versionScheme, String calverFormat, Optional<String> changelogUrl) {
        return new ApplicationConfigLoader.AppConfig() {
            @Override
            public String name() {
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
