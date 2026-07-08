package org.yardship.core.domain.primitives;

import org.junit.jupiter.api.Test;
import org.yardship.core.domain.primitives.CalverFormat;
import org.yardship.core.domain.primitives.CalverVersion;
import org.yardship.core.domain.primitives.ChangelogTemplate;
import org.yardship.core.domain.primitives.SemverVersion;
import org.yardship.core.domain.primitives.VersionScheme;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Behavior suite for {@link ChangelogTemplate} (ADR-0021: the changelog link is a read-time
 * projection from an app-level template).
 *
 * <p>Assumed API surface for the implementer:
 * <ul>
 *   <li>{@code ChangelogTemplate(String rawTemplate, VersionScheme scheme,
 *       Optional<CalverFormat> calverFormat)} — constructor. {@code calverFormat} is
 *       {@link Optional#empty()} for a semver app and the app's declared format for a calver
 *       app. Construction throws {@link IllegalArgumentException} when the template contains a
 *       placeholder that is unknown, illegal for the scheme (a semver token on a calver app or
 *       vice versa), or — for calver — a token symbol not present in the app's declared
 *       {@code calverFormat}. A token-free template is legal (a constant URL).</li>
 *   <li>{@code String resolve(VersionValue version)} — substitutes every placeholder with the
 *       corresponding component of {@code version} and returns the resolved URL string. Never
 *       null; the caller (the {@code ChangelogTemplates} wiring bean / REST projection) is
 *       responsible for only calling this when a known latest version exists.</li>
 * </ul>
 *
 * <p>This is a pure domain unit test — no Quarkus context needed.
 */
class ChangelogTemplateTests {

    // -----------------------------------------------------------------------
    // {version} — both schemes
    // -----------------------------------------------------------------------

    @Test
    void resolve_substitutesVersionToken_forSemverApp() {
        ChangelogTemplate template = new ChangelogTemplate(
                "https://github.com/argoproj/argo-cd/releases/tag/v{version}",
                VersionScheme.SEMVER,
                Optional.empty());

        String resolved = template.resolve(new SemverVersion("3.0.5"));

        assertEquals("https://github.com/argoproj/argo-cd/releases/tag/v3.0.5", resolved);
    }

    @Test
    void resolve_substitutesVersionToken_forCalverApp() {
        CalverFormat format = new CalverFormat("YY.0M");
        ChangelogTemplate template = new ChangelogTemplate(
                "https://documentation.ubuntu.com/release-notes/{version}/",
                VersionScheme.CALVER,
                Optional.of(format));

        String resolved = template.resolve(new CalverVersion("24.04", format));

        assertEquals("https://documentation.ubuntu.com/release-notes/24.04/", resolved);
    }

    // -----------------------------------------------------------------------
    // Semver component tokens
    // -----------------------------------------------------------------------

    @Test
    void resolve_substitutesMajorMinorPatch_forSemverApp() {
        ChangelogTemplate template = new ChangelogTemplate(
                "https://example.test/{major}/{minor}/{patch}",
                VersionScheme.SEMVER,
                Optional.empty());

        String resolved = template.resolve(new SemverVersion("3.10.5"));

        assertEquals("https://example.test/3/10/5", resolved);
    }

    // -----------------------------------------------------------------------
    // Calver format-symbol tokens — zero-padding preserved
    // -----------------------------------------------------------------------

    @Test
    void resolve_calverFormatSymbolTokens_preserveZeroPadding() {
        // {0M} must render as the displayed substring "05", not the re-rendered number "5".
        CalverFormat format = new CalverFormat("YY.0M.MICRO");
        ChangelogTemplate template = new ChangelogTemplate(
                "https://openwrt.org/releases/{YY}.{0M}/notes-{version}",
                VersionScheme.CALVER,
                Optional.of(format));

        String resolved = template.resolve(new CalverVersion("23.05.5", format));

        assertEquals("https://openwrt.org/releases/23.05/notes-23.05.5", resolved);
    }

    @Test
    void resolve_calverMicroToken_rendersDisplayedSubstring() {
        CalverFormat format = new CalverFormat("YY.0M.MICRO");
        ChangelogTemplate template = new ChangelogTemplate(
                "https://openwrt.org/releases/{YY}.{0M}/{MICRO}",
                VersionScheme.CALVER,
                Optional.of(format));

        String resolved = template.resolve(new CalverVersion("23.05.5", format));

        assertEquals("https://openwrt.org/releases/23.05/5", resolved);
    }

    @Test
    void resolve_calverTrailingTokenAbsentFromActualVersion_resolvesToEmptyString() {
        // The format declares MICRO (legal), but the actual latest version string omits the
        // trailing MICRO segment entirely — a legitimate application state, not a construction
        // error. The placeholder must resolve to "" rather than throw a NullPointerException.
        CalverFormat format = new CalverFormat("YY.0M.MICRO");
        ChangelogTemplate template = new ChangelogTemplate(
                "https://openwrt.org/releases/{YY}.{0M}/{MICRO}",
                VersionScheme.CALVER,
                Optional.of(format));

        String resolved = template.resolve(new CalverVersion("23.05", format));

        assertEquals("https://openwrt.org/releases/23.05/", resolved);
    }

    // -----------------------------------------------------------------------
    // Token-free constant template
    // -----------------------------------------------------------------------

    @Test
    void resolve_tokenFreeTemplate_isAConstantUrl_forSemverApp() {
        ChangelogTemplate template = new ChangelogTemplate(
                "https://docs.example.test/release-notes/",
                VersionScheme.SEMVER,
                Optional.empty());

        String resolved = template.resolve(new SemverVersion("1.2.3"));

        assertEquals("https://docs.example.test/release-notes/", resolved);
    }

    @Test
    void resolve_tokenFreeTemplate_isAConstantUrl_forCalverApp() {
        CalverFormat format = new CalverFormat("YY.0M");
        ChangelogTemplate template = new ChangelogTemplate(
                "https://docs.example.test/release-notes/",
                VersionScheme.CALVER,
                Optional.of(format));

        String resolved = template.resolve(new CalverVersion("24.04", format));

        assertEquals("https://docs.example.test/release-notes/", resolved);
    }

    // -----------------------------------------------------------------------
    // Construction throws — unknown token
    // -----------------------------------------------------------------------

    @Test
    void construction_throwsIllegalArgumentException_forUnknownToken() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChangelogTemplate(
                        "https://example.test/{bogus}",
                        VersionScheme.SEMVER,
                        Optional.empty()),
                "An unrecognised placeholder must fail fast at construction time");
    }

    // -----------------------------------------------------------------------
    // Construction throws — cross-scheme token
    // -----------------------------------------------------------------------

    @Test
    void construction_throwsIllegalArgumentException_forSemverTokenOnCalverApp() {
        CalverFormat format = new CalverFormat("YY.0M.MICRO");

        assertThrows(IllegalArgumentException.class,
                () -> new ChangelogTemplate(
                        "https://example.test/{major}",
                        VersionScheme.CALVER,
                        Optional.of(format)),
                "A semver-only token ({major}) must be illegal on a calver app");
    }

    @Test
    void construction_throwsIllegalArgumentException_forPatchTokenOnCalverApp() {
        // Explicit acceptance-criterion case: {patch} on a calver app.
        CalverFormat format = new CalverFormat("YY.0M.MICRO");

        assertThrows(IllegalArgumentException.class,
                () -> new ChangelogTemplate(
                        "https://example.test/{patch}",
                        VersionScheme.CALVER,
                        Optional.of(format)),
                "{patch} is a semver-only token and must be illegal on a calver app");
    }

    @Test
    void construction_throwsIllegalArgumentException_forCalverTokenOnSemverApp() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChangelogTemplate(
                        "https://example.test/{YY}",
                        VersionScheme.SEMVER,
                        Optional.empty()),
                "A calver-format-symbol token must be illegal on a semver app");
    }

    // -----------------------------------------------------------------------
    // Construction throws — calver token not present in the app's declared format
    // -----------------------------------------------------------------------

    @Test
    void construction_throwsIllegalArgumentException_forCalverTokenAbsentFromDeclaredFormat() {
        // The app's format is "YY.0M" — it has no MICRO token, so {MICRO} must be illegal
        // even though MICRO is a legitimate calver.org token in general.
        CalverFormat format = new CalverFormat("YY.0M");

        assertThrows(IllegalArgumentException.class,
                () -> new ChangelogTemplate(
                        "https://example.test/{MICRO}",
                        VersionScheme.CALVER,
                        Optional.of(format)),
                "A calver token not present in the app's declared calver-format must be illegal");
    }

    // -----------------------------------------------------------------------
    // Whole-example resolutions from the ADR / issue description
    // -----------------------------------------------------------------------

    @Test
    void resolve_argoCdExample_resolvesGithubTagUrl() {
        ChangelogTemplate template = new ChangelogTemplate(
                "https://github.com/argoproj/argo-cd/releases/tag/v{version}",
                VersionScheme.SEMVER,
                Optional.empty());

        assertEquals(
                "https://github.com/argoproj/argo-cd/releases/tag/v3.0.5",
                template.resolve(new SemverVersion("3.0.5")));
    }

    @Test
    void resolve_openWrtExample_resolvesReleaseNotesUrl_withZeroPaddingPreserved() {
        CalverFormat format = new CalverFormat("YY.0M.MICRO");
        ChangelogTemplate template = new ChangelogTemplate(
                "https://openwrt.org/releases/{YY}.{0M}/notes-{version}",
                VersionScheme.CALVER,
                Optional.of(format));

        assertEquals(
                "https://openwrt.org/releases/23.05/notes-23.05.5",
                template.resolve(new CalverVersion("23.05.5", format)));
    }
}
