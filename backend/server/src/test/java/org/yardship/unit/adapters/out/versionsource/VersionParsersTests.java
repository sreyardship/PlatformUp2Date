package org.yardship.unit.adapters.out.versionsource;

import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.adapters.out.versionsource.VersionParsers;
import org.yardship.core.domain.exceptions.InvalidVersionException;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import org.yardship.core.domain.primitives.VersionValue;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link VersionParsers} — the eager, per-app {@link VersionParser} lookup (issue
 * 01) that {@code VersionSourceResolver} must consume instead of constructing parsers inline.
 * Mirrors {@code ChangelogTemplatesTests}: driven entirely through the package-visible
 * {@code List<AppConfig>} constructor, with fake {@link ApplicationConfigLoader.AppConfig}
 * implementations following the same anonymous-class pattern as {@code VersionSourceResolverTests}.
 */
class VersionParsersTests {

    @Test
    void resolvesASemverParser_forASemverApp() {
        ApplicationConfigLoader.AppConfig app = app("alpha", VersionScheme.SEMVER, null);

        VersionParsers parsers = new VersionParsers(List.of(app));

        Optional<VersionParser> parser = parsers.forApp("alpha");
        assertTrue(parser.isPresent(), "a semver app resolves to a present parser");
        VersionValue parsed = parser.get().parse("3.10.5");
        assertEquals(VersionScheme.SEMVER, parsed.scheme());
        assertEquals("3.10.5", parsed.value());
    }

    @Test
    void resolvesACalverParser_honouringThatAppsDeclaredFormat() {
        ApplicationConfigLoader.AppConfig shortFormatApp =
                app("calver-a", VersionScheme.CALVER, "YY.0M.MICRO");
        ApplicationConfigLoader.AppConfig longFormatApp =
                app("calver-b", VersionScheme.CALVER, "YYYY.MM");

        VersionParsers parsers = new VersionParsers(List.of(shortFormatApp, longFormatApp));

        VersionValue parsed = parsers.forApp("calver-a").get().parse("24.04.5");
        assertEquals(VersionScheme.CALVER, parsed.scheme());
        assertEquals("24.04.5", parsed.value());

        // The same raw string is illegal against calver-b's own declared format ("YYYY" demands 4
        // digits, "24" has only 2) — proving each app's parser honours ITS OWN format, not a shared
        // or generic one.
        assertThrows(InvalidVersionException.class, () ->
                parsers.forApp("calver-b").get().parse("24.04.5"));
    }

    @Test
    void unconfiguredAppName_yieldsAnAbsentParser() {
        ApplicationConfigLoader.AppConfig app = app("alpha", VersionScheme.SEMVER, null);

        VersionParsers parsers = new VersionParsers(List.of(app));

        assertTrue(parsers.forApp("unconfigured-app").isEmpty());
    }

    @Test
    void failsFast_onACalverAppWithoutAUsableCalverFormat() {
        ApplicationConfigLoader.AppConfig missingFormat = app("my-app", VersionScheme.CALVER, null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new VersionParsers(List.of(missingFormat)));

        assertTrue(ex.getMessage().contains("my-app"),
                "the wrapped error must name the offending app; was: " + ex.getMessage());
    }

    @Test
    void failsFast_onACalverAppWithABlankCalverFormat() {
        ApplicationConfigLoader.AppConfig blankFormat = app("blank-app", VersionScheme.CALVER, "   ");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                new VersionParsers(List.of(blankFormat)));

        assertTrue(ex.getMessage().contains("blank-app"),
                "the wrapped error must name the offending app; was: " + ex.getMessage());
    }

    // --- fakes --------------------------------------------------------------------------------

    private static ApplicationConfigLoader.AppConfig app(
            String name, VersionScheme versionScheme, String calverFormat) {
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
                return Optional.empty();
            }
        };
    }
}
