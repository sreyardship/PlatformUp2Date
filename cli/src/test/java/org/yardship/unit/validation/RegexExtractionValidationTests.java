package org.yardship.unit.validation;

import org.junit.jupiter.api.Test;
import org.yardship.cli.outcome.RegexCandidate;
import org.yardship.cli.outcome.ValidationOutcome;
import org.yardship.cli.validation.RegexExtractionValidation;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RegexExtractionValidation}, the use case at the heart of the {@code regex}
 * subcommand. These pin the "largest wins, report every candidate" contract at the cheapest
 * possible seam: pure strings in, {@link ValidationOutcome} out — no HTTP, no filesystem, no CLI
 * wiring. See {@code RegexCommandWiringTests} (system level) for the end-to-end picocli path, and
 * {@code LiveHttpBodySourceIT}/{@code OfflineBodySourceIT} for the body-acquisition adapters.
 */
class RegexExtractionValidationTests {

    private final RegexExtractionValidation validation = new RegexExtractionValidation();

    private static final VersionParser SEMVER = new VersionParser(VersionScheme.SEMVER);
    private static final VersionParser CALVER_YY_0M = new VersionParser(VersionScheme.CALVER, "YY.0M");
    private static final VersionParser CALVER_YY_MM = new VersionParser(VersionScheme.CALVER, "YY.MM");

    @Test
    void multipleMatches_largestParseableWins() {
        ValidationOutcome outcome = validation.validate(
                "1.2.0\n2.0.0\n1.9.9", "(\\d+\\.\\d+\\.\\d+)", SEMVER);

        ValidationOutcome.Ok ok = assertInstanceOf(ValidationOutcome.Ok.class, outcome);
        assertEquals(ValidationOutcome.Ok.EXIT_CODE, ok.exitCode());
        assertEquals(3, ok.candidates().size());
        assertEquals("2.0.0", ok.winner().rawText());
        assertTrue(ok.winner().isParsed());
    }

    @Test
    void zeroMatches_isValidButEmpty() {
        ValidationOutcome outcome = validation.validate(
                "no version tokens here", "Version: (\\S+)", SEMVER);

        ValidationOutcome.ValidButEmpty empty =
                assertInstanceOf(ValidationOutcome.ValidButEmpty.class, outcome);
        assertEquals(ValidationOutcome.ValidButEmpty.EXIT_CODE, empty.exitCode());
        assertTrue(empty.candidates().isEmpty());
    }

    @Test
    void matchesFoundButAllUnparseable_isValidButEmpty() {
        ValidationOutcome outcome = validation.validate(
                "Version: not-semver\nVersion: also-not-semver", "Version: (\\S+)", SEMVER);

        ValidationOutcome.ValidButEmpty empty =
                assertInstanceOf(ValidationOutcome.ValidButEmpty.class, outcome);
        assertEquals(2, empty.candidates().size());
        assertTrue(empty.candidates().stream().noneMatch(RegexCandidate::isParsed),
                "every candidate must be recorded, and each must be marked unparsed");
    }

    @Test
    void partiallyUnparseableMatches_stillReportsAllCandidates_andWinnerIsLargestParseable() {
        ValidationOutcome outcome = validation.validate(
                "token: not-a-semver\ntoken: 2.0.0", "token: (\\S+)", SEMVER);

        ValidationOutcome.Ok ok = assertInstanceOf(ValidationOutcome.Ok.class, outcome);
        assertEquals(2, ok.candidates().size(), "the unparseable candidate must still be reported");
        assertEquals("2.0.0", ok.winner().rawText());
    }

    @Test
    void invalidRegexSyntax_isConfigInvalid() {
        ValidationOutcome outcome = validation.validate("1.2.3", "(unclosed", SEMVER);

        ValidationOutcome.ConfigInvalid invalid =
                assertInstanceOf(ValidationOutcome.ConfigInvalid.class, outcome);
        assertEquals(ValidationOutcome.ConfigInvalid.EXIT_CODE, invalid.exitCode());
    }

    @Test
    void regexWithNoCaptureGroup_isConfigInvalid() {
        ValidationOutcome outcome = validation.validate("1.2.3", "\\d+\\.\\d+\\.\\d+", SEMVER);

        assertInstanceOf(ValidationOutcome.ConfigInvalid.class, outcome,
                "a regex with no capture group 1 has nothing to parse and must be rejected as config");
    }

    /**
     * "Largest" must be scheme-aware, not a naive string/lexicographic sort. Under the non-zero-padded
     * {@code YY.MM} calver format, "24.9" and "24.10" both PARSE successfully, so this genuinely pits
     * lexicographic order ("24.9" > "24.10" as strings, since {@code '9' > '1'}) against calendar/
     * version-aware order (September &lt; October, so 24.10 must win).
     */
    @Test
    void calverScheme_largestIsVersionAware_notLexicographic() {
        ValidationOutcome outcome = validation.validate(
                "Version: 24.9\nVersion: 24.10", "Version: (\\S+)", CALVER_YY_MM);

        ValidationOutcome.Ok ok = assertInstanceOf(ValidationOutcome.Ok.class, outcome);
        assertEquals("24.10", ok.winner().rawText(),
                "24.10 must beat 24.9 under calver comparison even though '24.10' < '24.9' lexicographically");
    }

    @Test
    void usesCaptureGroup1_notFullMatch() {
        ValidationOutcome outcome = validation.validate(
                "release v1.0.0\nrelease v1.3.0", "release v(\\d+\\.\\d+\\.\\d+)", SEMVER);

        ValidationOutcome.Ok ok = assertInstanceOf(ValidationOutcome.Ok.class, outcome);
        assertEquals("1.3.0", ok.winner().rawText());
    }
}
