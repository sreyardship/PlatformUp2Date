package org.yardship.unit.adapters.out.versionsource;

import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.adapters.out.versionsource.VersionParsers;
import org.yardship.adapters.out.versionsource.configerror.ConfigError;
import org.yardship.adapters.out.versionsource.configerror.ConfigErrorScope;
import org.yardship.core.domain.exceptions.InvalidVersionException;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import org.yardship.core.domain.primitives.VersionValue;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the eager, per-app {@link VersionParser} lookup consumed by
 * {@code VersionSourceResolver}.
 * Mirrors {@code ChangelogTemplatesTests}: driven entirely through the package-visible
 * {@code List<AppConfig>} constructor, with fake {@link ApplicationConfigLoader.AppConfig}
 * implementations following the same anonymous-class pattern as {@code VersionSourceResolverTests}.
 *
 * <p>Issue 03 / ADR-0032: a missing or invalid {@code calver-format} no longer fails boot. It now
 * records exactly one {@link ConfigErrorScope#APP}-scope {@link ConfigError} — the Version scheme is
 * declared once per app and shared by both legs, so neither leg's value is parseable — and
 * {@link VersionParsers#forApp} simply returns {@link Optional#empty()} for that app, a legitimate,
 * expected state rather than a bug.
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

    // --- issue 02 / ADR-0032: an unnamed app must not break construction for its siblings -----

    @Test
    void unnamedApp_isSkipped_ratherThanBreakingConstructionOrItsNamedSibling() {
        ApplicationConfigLoader.AppConfig unnamed = unnamedApp(VersionScheme.SEMVER, null);
        ApplicationConfigLoader.AppConfig named = app("alpha", VersionScheme.SEMVER, null);

        // Must not throw: Map.copyOf/put on a null key would NPE if the unnamed app were not
        // skipped before reaching the map (the exact boot failure this slice exists to eliminate).
        VersionParsers parsers = new VersionParsers(List.of(unnamed, named));

        assertTrue(parsers.forApp("alpha").isPresent(), "the named sibling's entry must be intact");
        VersionValue parsed = parsers.forApp("alpha").get().parse("1.2.3");
        assertEquals("1.2.3", parsed.value());
    }

    // --- issue 03 / ADR-0032: a bad calver-format degrades, at APP scope, instead of failing boot -

    @Test
    void missingCalverFormat_boots_andRecordsOneAppScopeConfigError_ratherThanThrowing() {
        ApplicationConfigLoader.AppConfig missingFormat = app("my-app", VersionScheme.CALVER, null);

        VersionParsers parsers = new VersionParsers(List.of(missingFormat));

        assertEquals(1, parsers.configErrors().size(),
                "a missing calver-format must record exactly one config error, not throw");
        ConfigError error = parsers.configErrors().get(0);
        assertEquals("my-app", error.application());
        assertEquals(ConfigErrorScope.APP, error.scope(),
                "the Version scheme is declared once per app and shared by both legs, so this is an "
                        + "APP-scope error, not CURRENT/LATEST");
        assertTrue(error.reason().contains("my-app"),
                "the recorded reason must name the offending app; was: " + error.reason());
    }

    @Test
    void blankCalverFormat_boots_andRecordsOneAppScopeConfigError_ratherThanThrowing() {
        ApplicationConfigLoader.AppConfig blankFormat = app("blank-app", VersionScheme.CALVER, "   ");

        VersionParsers parsers = new VersionParsers(List.of(blankFormat));

        assertEquals(1, parsers.configErrors().size());
        ConfigError error = parsers.configErrors().get(0);
        assertEquals("blank-app", error.application());
        assertEquals(ConfigErrorScope.APP, error.scope());
        assertTrue(error.reason().contains("blank-app"),
                "the recorded reason must name the offending app; was: " + error.reason());
    }

    @Test
    void invalidCalverFormat_yieldsAnAbsentParser_forThatApp() {
        ApplicationConfigLoader.AppConfig missingFormat = app("my-app", VersionScheme.CALVER, null);

        VersionParsers parsers = new VersionParsers(List.of(missingFormat));

        assertTrue(parsers.forApp("my-app").isEmpty(),
                "an absent parser is now a legitimate, expected state for a broken calver app");
    }

    @Test
    void invalidCalverFormat_doesNotAffectASiblingAppsParser() {
        ApplicationConfigLoader.AppConfig broken = app("broken-app", VersionScheme.CALVER, null);
        ApplicationConfigLoader.AppConfig healthy = app("healthy-app", VersionScheme.SEMVER, null);

        VersionParsers parsers = new VersionParsers(List.of(broken, healthy));

        assertTrue(parsers.forApp("broken-app").isEmpty());
        assertTrue(parsers.forApp("healthy-app").isPresent(),
                "the sibling app's parser must be entirely unaffected");
        VersionValue parsed = parsers.forApp("healthy-app").get().parse("1.2.3");
        assertEquals("1.2.3", parsed.value());

        assertEquals(1, parsers.configErrors().size(),
                "only the broken app's own defect is recorded; the healthy sibling contributes none");
        assertEquals("broken-app", parsers.configErrors().get(0).application());
    }

    @Test
    void validConfig_recordsNoConfigErrors() {
        ApplicationConfigLoader.AppConfig semverApp = app("alpha", VersionScheme.SEMVER, null);
        ApplicationConfigLoader.AppConfig calverApp = app("beta", VersionScheme.CALVER, "YYYY.0M.MICRO");

        VersionParsers parsers = new VersionParsers(List.of(semverApp, calverApp));

        assertTrue(parsers.configErrors().isEmpty(),
                "a fully valid config must record no config errors");
    }

    // --- an undeclared exception is a defect in OUR code, not the operator's config -----------

    @Test
    void undeclaredExceptionFromAnAccessor_isRecordedAtAppScope_andLoggedAtError_notThrown() {
        // versionScheme() is called directly inside buildParser's own try, so an undeclared
        // RuntimeException from it (standing in for a defect in OUR code — an NPE, an Arc failure —
        // rather than an operator's config) must still be caught by VersionParsers' own
        // catch (RuntimeException) clause: recorded as an APP-scope config error and logged at
        // ERROR, never left to propagate and kill the boot.
        ApplicationConfigLoader.AppConfig defective = new ApplicationConfigLoader.AppConfig() {
            @Override
            public Optional<String> name() {
                return Optional.of("defective-app");
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
                throw new NullPointerException("npe boom");
            }

            @Override
            public Optional<String> calverFormat() {
                return Optional.empty();
            }

            @Override
            public Optional<String> changelogUrl() {
                return Optional.empty();
            }
        };

        try (TestLogHandler logs = new TestLogHandler(VersionParsers.class.getName())) {
            VersionParsers parsers = new VersionParsers(List.of(defective));

            assertEquals(1, parsers.configErrors().size());
            ConfigError error = parsers.configErrors().get(0);
            assertEquals("defective-app", error.application());
            assertEquals(ConfigErrorScope.APP, error.scope(),
                    "an undeclared defect still degrades at APP scope, same as a declared one");
            assertTrue(parsers.forApp("defective-app").isEmpty());

            assertFalse(logs.recordsAtLevel(java.util.logging.Level.SEVERE).isEmpty(),
                    "an undeclared RuntimeException must be logged at ERROR (java.util.logging SEVERE)");
        }
    }

    // --- fakes --------------------------------------------------------------------------------

    private static ApplicationConfigLoader.AppConfig unnamedApp(
            VersionScheme versionScheme, String calverFormat) {
        return namedOrUnnamed(Optional.empty(), versionScheme, calverFormat);
    }

    private static ApplicationConfigLoader.AppConfig app(
            String name, VersionScheme versionScheme, String calverFormat) {
        return namedOrUnnamed(Optional.of(name), versionScheme, calverFormat);
    }

    private static ApplicationConfigLoader.AppConfig namedOrUnnamed(
            Optional<String> name, VersionScheme versionScheme, String calverFormat) {
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
                return Optional.empty();
            }
        };
    }
}
