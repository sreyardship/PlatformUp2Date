package org.yardship.unit.adapters.out.versionsource;

import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.adapters.out.versionsource.ChangelogTemplates;
import org.yardship.adapters.out.versionsource.configerror.ConfigError;
import org.yardship.adapters.out.versionsource.configerror.ConfigErrorScope;
import org.yardship.core.domain.primitives.ChangelogTemplate;
import org.yardship.core.domain.primitives.SemverVersion;
import org.yardship.core.domain.primitives.VersionScheme;

import java.util.List;
import java.util.Optional;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the eager, per-app {@link ChangelogTemplate} lookup (ADR-0021).
 *
 * <p>Issue 03 / ADR-0032: an illegal {@code changelog-url} template no longer fails boot. It now
 * records exactly one {@link ConfigErrorScope#CHANGELOG}-scope {@link ConfigError} — the one scope
 * where a config error degrades nothing: the app keeps scraping normally on both legs, and only the
 * Changelog link is absent (the same state ADR-0021 already defines for "no template configured").
 *
 * <p>Driven entirely through the package-visible {@code List<AppConfig>} constructor — "visible
 * for testing: lets tests drive this bean with plain fakes and no CDI container" — with fake
 * {@link ApplicationConfigLoader.AppConfig} implementations following the same anonymous-class
 * pattern as {@code VersionSourceResolverTests}.
 */
class ChangelogTemplatesTests {

    @Test
    void illegalTemplate_boots_andRecordsOneChangelogScopeConfigError_ratherThanThrowing() {
        // "{major}" is a semver-component token, illegal on a CALVER app.
        ApplicationConfigLoader.AppConfig app = app("my-app", VersionScheme.CALVER, "YY.0M.MICRO",
                Optional.of("https://example.com/changelog/{major}"));

        ChangelogTemplates templates = new ChangelogTemplates(List.of(app));

        assertEquals(1, templates.configErrors().size(),
                "an illegal template must record exactly one config error, not throw");
        ConfigError error = templates.configErrors().get(0);
        assertEquals("my-app", error.application());
        assertEquals(ConfigErrorScope.CHANGELOG, error.scope(),
                "a broken template degrades nothing else about the app, so this must be "
                        + "CHANGELOG scope, not APP/CURRENT/LATEST");
        assertTrue(error.reason().contains("my-app"),
                "the recorded reason must name the offending app; was: " + error.reason());
        assertTrue(error.reason().contains("major"),
                "the recorded reason must name the offending token; was: " + error.reason());
    }

    @Test
    void illegalTemplate_yieldsAnAbsentTemplate_forThatApp() {
        ApplicationConfigLoader.AppConfig app = app("my-app", VersionScheme.CALVER, "YY.0M.MICRO",
                Optional.of("https://example.com/changelog/{major}"));

        ChangelogTemplates templates = new ChangelogTemplates(List.of(app));

        assertTrue(templates.forApp("my-app").isEmpty(),
                "an absent template is the same state ADR-0021 already defines for no template "
                        + "configured at all");
    }

    @Test
    void illegalTemplate_doesNotAffectASiblingAppsTemplate() {
        ApplicationConfigLoader.AppConfig broken = app("broken-app", VersionScheme.CALVER,
                "YY.0M.MICRO", Optional.of("https://example.com/changelog/{major}"));
        ApplicationConfigLoader.AppConfig healthy = app("healthy-app", VersionScheme.SEMVER, null,
                Optional.of("https://example.com/changelog/v{major}.{minor}.{patch}"));

        ChangelogTemplates templates = new ChangelogTemplates(List.of(broken, healthy));

        assertTrue(templates.forApp("broken-app").isEmpty());
        assertTrue(templates.forApp("healthy-app").isPresent(),
                "the sibling app's template must be entirely unaffected");
        assertEquals("https://example.com/changelog/v3.10.5",
                templates.forApp("healthy-app").get().resolve(new SemverVersion("3.10.5")));

        assertEquals(1, templates.configErrors().size(),
                "only the broken app's own defect is recorded; the healthy sibling contributes none");
        assertEquals("broken-app", templates.configErrors().get(0).application());
    }

    @Test
    void validConfig_recordsNoConfigErrors() {
        ApplicationConfigLoader.AppConfig withTemplate = app("alpha", VersionScheme.SEMVER, null,
                Optional.of("https://example.com/changelog/v{major}.{minor}.{patch}"));
        ApplicationConfigLoader.AppConfig withoutTemplate =
                app("beta", VersionScheme.SEMVER, null, Optional.empty());

        ChangelogTemplates templates = new ChangelogTemplates(List.of(withTemplate, withoutTemplate));

        assertTrue(templates.configErrors().isEmpty(),
                "a fully valid config — including an app with no changelog-url at all — must record "
                        + "no config errors");
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

    // --- issue 03 / ADR-0032 review fix: the app-scope-skip interaction, pinned exactly -------
    //
    // The defect this reviewer round exists to catch: ChangelogTemplates used to build the app's
    // CalverFormat OUTSIDE its own try, so a broken calver-format was reported a second time here,
    // at CHANGELOG scope, with a message that didn't even name the app — even though VersionParsers
    // already owns and reports that exact defect at APP scope. These two tests pin the fix: the
    // skip fires (no double-report) exactly when the format is actually broken, and does NOT fire
    // merely because the app is CALVER.

    @Test
    void calverAppWithNoCalverFormat_recordsNoChangelogError_becauseVersionParsersOwnsThatDefect() {
        // No calver-format at all — VersionParsers would record this app's defect at APP scope.
        // ChangelogTemplates must skip the app entirely: no template, and no CHANGELOG-scope error
        // of its own, so the defect is never reported twice.
        ApplicationConfigLoader.AppConfig app = app("my-app", VersionScheme.CALVER, null,
                Optional.of("https://example.com/changelog/v{version}"));

        ChangelogTemplates templates = new ChangelogTemplates(List.of(app));

        assertTrue(templates.configErrors().isEmpty(),
                "a missing calver-format is VersionParsers' defect to report at APP scope; "
                        + "ChangelogTemplates must record nothing of its own for it");
        assertTrue(templates.forApp("my-app").isEmpty(),
                "no template is registered for an app whose scheme itself failed to build");
    }

    @Test
    void calverAppWithAValidCalverFormat_stillGetsItsTemplate_theSkipIsNotOverBroad() {
        // A CALVER app with a legal calver-format AND a legal changelog-url must still resolve a
        // template normally — proving the skip fires only for a genuinely broken calver-format, not
        // for every CALVER app.
        ApplicationConfigLoader.AppConfig app = app("my-app", VersionScheme.CALVER, "YYYY.0M.MICRO",
                Optional.of("https://example.com/changelog/{YYYY}.{0M}.{MICRO}"));

        ChangelogTemplates templates = new ChangelogTemplates(List.of(app));

        assertTrue(templates.configErrors().isEmpty());
        Optional<ChangelogTemplate> template = templates.forApp("my-app");
        assertTrue(template.isPresent(),
                "a CALVER app with a valid calver-format must still get its template");
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

    @Test
    void undeclaredExceptionFromAnAccessor_isRecordedAtChangelogScope_andLoggedAtError_notThrown() {
        // ADR-0032's promise that one app can never take the fleet down holds absolutely, so an
        // exception we never declared — read here from versionScheme(), which sits on the path to
        // the template — must degrade this app rather than escape the constructor.
        ApplicationConfigLoader.AppConfig throwing = new ApplicationConfigLoader.AppConfig() {
            @Override
            public Optional<String> name() {
                return Optional.of("exploding-app");
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
                throw new NullPointerException("boom from versionScheme()");
            }

            @Override
            public Optional<String> calverFormat() {
                return Optional.empty();
            }

            @Override
            public Optional<String> changelogUrl() {
                return Optional.of("https://example.test/{version}");
            }
        };

        try (TestLogHandler logs = new TestLogHandler(ChangelogTemplates.class.getName())) {
            ChangelogTemplates templates = new ChangelogTemplates(List.of(throwing));

            List<ConfigError> errors = templates.configErrors();
            assertEquals(1, errors.size(), "an undeclared exception must be recorded, not rethrown");
            assertEquals(ConfigErrorScope.CHANGELOG, errors.get(0).scope());
            assertEquals("exploding-app", errors.get(0).application());
            assertTrue(templates.forApp("exploding-app").isEmpty(),
                    "no template can be registered for an app whose config accessor blew up");
            assertFalse(logs.recordsAtLevel(Level.SEVERE).isEmpty(),
                    "an exception we did not declare is a defect in our own code and logs at ERROR");
        }
    }

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
