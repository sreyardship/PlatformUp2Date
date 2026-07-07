package org.yardship.unit.validation;

import org.junit.jupiter.api.Test;
import org.yardship.cli.outcome.ValidationOutcome;
import org.yardship.cli.validation.ChangelogResolutionValidation;
import org.yardship.core.domain.primitives.CalverFormat;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ChangelogResolutionValidation}, the use case behind the {@code changelog}
 * subcommand. This is a PURE-FUNCTION check — no {@link org.yardship.cli.port.BodySource}, no
 * network — so these tests pin the "parse --version, construct+resolve a
 * {@link org.yardship.core.domain.primitives.ChangelogTemplate}" contract directly: strings/parser
 * in, {@link ValidationOutcome} out. See {@code ChangelogCommandWiringTests} (system level) for the
 * end-to-end picocli path.
 *
 * <p>Illegal-placeholder and calver-token-mapping behaviour is already exhaustively unit-tested on
 * {@code ChangelogTemplate} itself (domain module); these tests only prove
 * {@code ChangelogResolutionValidation} translates that primitive's fail-fast constructor and
 * {@code resolve()} correctly into the sealed {@link ValidationOutcome} model — one representative
 * case per outcome kind, not an exhaustive re-test of the primitive's own placeholder rules.
 */
class ChangelogResolutionValidationTests {

    private final ChangelogResolutionValidation validation = new ChangelogResolutionValidation();

    private static final VersionParser SEMVER = new VersionParser(VersionScheme.SEMVER);

    @Test
    void semverTemplate_legalTokens_resolvesToUrl() {
        ValidationOutcome outcome = validation.validate(
                "https://example.com/{major}.{minor}.{patch}", "1.2.3", SEMVER, Optional.empty());

        ValidationOutcome.ChangelogOk ok = assertInstanceOf(ValidationOutcome.ChangelogOk.class, outcome);
        assertEquals(ValidationOutcome.ChangelogOk.EXIT_CODE, ok.exitCode());
        assertEquals("https://example.com/1.2.3", ok.resolvedUrl());
    }

    @Test
    void versionToken_legalForBothSchemes_resolvesToRawParsedValue() {
        ValidationOutcome outcome = validation.validate(
                "https://example.com/releases/{version}", "1.2.3", SEMVER, Optional.empty());

        ValidationOutcome.ChangelogOk ok = assertInstanceOf(ValidationOutcome.ChangelogOk.class, outcome);
        assertEquals("https://example.com/releases/1.2.3", ok.resolvedUrl());
    }

    @Test
    void tokenFreeTemplate_isConstantUrl_resolvesUnchanged() {
        ValidationOutcome outcome = validation.validate(
                "https://example.com/CHANGELOG.md", "1.2.3", SEMVER, Optional.empty());

        ValidationOutcome.ChangelogOk ok = assertInstanceOf(ValidationOutcome.ChangelogOk.class, outcome);
        assertEquals("https://example.com/CHANGELOG.md", ok.resolvedUrl());
    }

    @Test
    void semverScheme_calverToken_isConfigInvalid_namingTheToken() {
        ValidationOutcome outcome = validation.validate(
                "https://example.com/{YY}", "1.2.3", SEMVER, Optional.empty());

        ValidationOutcome.ConfigInvalid invalid =
                assertInstanceOf(ValidationOutcome.ConfigInvalid.class, outcome);
        assertEquals(ValidationOutcome.ConfigInvalid.EXIT_CODE, invalid.exitCode());
        assertTrue(invalid.message().contains("{YY}"),
                "message must name the offending placeholder verbatim, as ChangelogTemplate's constructor does");
    }

    @Test
    void semverScheme_majorToken_isLegal_calverToken_isIllegal_namingIt() {
        // major/minor/patch are legal only for SEMVER; a calver symbol like MICRO is never legal
        // on a semver app regardless of any calver-format (none is even supplied here).
        ValidationOutcome outcome = validation.validate(
                "https://example.com/{MICRO}", "1.2.3", SEMVER, Optional.empty());

        ValidationOutcome.ConfigInvalid invalid =
                assertInstanceOf(ValidationOutcome.ConfigInvalid.class, outcome);
        assertTrue(invalid.message().contains("{MICRO}"));
    }

    @Test
    void calverTemplate_declaredTokens_resolveToDisplayedValues_zeroPaddingPreserved() {
        CalverFormat format = new CalverFormat("YY.0M.MICRO");
        VersionParser parser = new VersionParser(VersionScheme.CALVER, "YY.0M.MICRO");

        ValidationOutcome outcome = validation.validate(
                "https://example.com/{YY}/{0M}/{MICRO}", "23.05.7", parser, Optional.of(format));

        ValidationOutcome.ChangelogOk ok = assertInstanceOf(ValidationOutcome.ChangelogOk.class, outcome);
        assertEquals("https://example.com/23/05/7", ok.resolvedUrl(),
                "0M's zero-padding must be preserved as displayed, not re-rendered as a bare number");
    }

    @Test
    void calverToken_notDeclaredInFormat_isConfigInvalid_namingIt() {
        CalverFormat format = new CalverFormat("YY.0M");
        VersionParser parser = new VersionParser(VersionScheme.CALVER, "YY.0M");

        ValidationOutcome outcome = validation.validate(
                "https://example.com/{MICRO}", "23.05", parser, Optional.of(format));

        ValidationOutcome.ConfigInvalid invalid =
                assertInstanceOf(ValidationOutcome.ConfigInvalid.class, outcome);
        assertTrue(invalid.message().contains("{MICRO}"),
                "MICRO is a real calver.org token but not declared in this app's calver-format 'YY.0M'");
    }

    @Test
    void calverTemplate_trailingDeclaredTokenAbsentFromVersion_rendersEmpty_notCrash() {
        // Mirrors ChangelogTemplate's own doc comment example: format YY.0M.MICRO but the actual
        // version string is "23.05" (MICRO trailing, declared but not present in this app instance).
        CalverFormat format = new CalverFormat("YY.0M.MICRO");
        VersionParser parser = new VersionParser(VersionScheme.CALVER, "YY.0M.MICRO");

        ValidationOutcome outcome = validation.validate(
                "https://example.com/{YY}.{0M}.{MICRO}", "23.05", parser, Optional.of(format));

        ValidationOutcome.ChangelogOk ok = assertInstanceOf(ValidationOutcome.ChangelogOk.class, outcome);
        assertEquals("https://example.com/23.05.", ok.resolvedUrl(),
                "the trailing declared-but-absent MICRO token must render as empty string, not crash");
    }

    @Test
    void versionDoesNotParseUnderScheme_isConfigInvalid_notCrash() {
        ValidationOutcome outcome = validation.validate(
                "https://example.com/{major}.{minor}.{patch}", "not-a-semver", SEMVER, Optional.empty());

        ValidationOutcome.ConfigInvalid invalid =
                assertInstanceOf(ValidationOutcome.ConfigInvalid.class, outcome);
        assertEquals(ValidationOutcome.ConfigInvalid.EXIT_CODE, invalid.exitCode());
    }
}
