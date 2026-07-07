package org.yardship.unit.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.yardship.cli.outcome.CalverMapping;
import org.yardship.cli.outcome.ValidationOutcome;
import org.yardship.cli.validation.CalverFormatValidation;
import org.yardship.core.domain.primitives.CalverFormat;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CalverFormatValidation}, the use case behind the {@code calver}
 * subcommand. This is a PURE-FUNCTION check — no {@link org.yardship.cli.port.BodySource}, no
 * network, and (unlike {@code changelog}) no {@code --scheme} flag: the scheme is implicitly
 * {@code CALVER}, so {@code validate} builds its own {@code CalverFormat}/{@code VersionParser}
 * internally from the raw {@code format} string. Mirrors
 * {@code ChangelogResolutionValidationTests}' structure (issue 04), the closest architectural
 * precedent: pure function, two failure modes both mapped to {@link ValidationOutcome.ConfigInvalid}.
 *
 * <p>See {@code CalverCommandWiringTests} (system level) for the end-to-end picocli path.
 */
class CalverFormatValidationTests {

    private final CalverFormatValidation validation = new CalverFormatValidation();

    @Test
    void validFormatAndVersion_resolvesToTokenValueMapping_zeroPaddingPreserved() {
        ValidationOutcome outcome = validation.validate("YY.0M.MICRO", "23.05.5");

        ValidationOutcome.CalverOk ok = assertInstanceOf(ValidationOutcome.CalverOk.class, outcome);
        assertEquals(ValidationOutcome.CalverOk.EXIT_CODE, ok.exitCode());

        CalverMapping mapping = ok.mapping();
        assertEquals(3, mapping.tokens().size());
        assertEquals(new CalverMapping.TokenDisplay("YY", "23"), mapping.tokens().get(0));
        assertEquals(new CalverMapping.TokenDisplay("0M", "05"), mapping.tokens().get(1),
                "0M's zero-padding must be preserved as displayed, not re-rendered as a bare number");
        assertEquals(new CalverMapping.TokenDisplay("MICRO", "5"), mapping.tokens().get(2));
    }

    @Test
    void trailingDeclaredTokenAbsentFromVersion_isOmittedFromMapping_notCrash() {
        // Mirrors ChangelogTemplate's own doc example: format YY.0M.MICRO but the actual version
        // string is "23.05" (MICRO trailing, declared but not present in this instance).
        ValidationOutcome outcome = validation.validate("YY.0M.MICRO", "23.05");

        ValidationOutcome.CalverOk ok = assertInstanceOf(ValidationOutcome.CalverOk.class, outcome);
        CalverMapping mapping = ok.mapping();

        assertEquals(2, mapping.tokens().size(),
                "the trailing declared-but-absent MICRO token must be omitted, not shown with a blank value");
        assertEquals(new CalverMapping.TokenDisplay("YY", "23"), mapping.tokens().get(0));
        assertEquals(new CalverMapping.TokenDisplay("0M", "05"), mapping.tokens().get(1));
        assertTrue(mapping.tokens().stream().noneMatch(t -> t.symbol().equals("MICRO")));
    }

    @Test
    void malformedFormat_isConfigInvalid_namingTheProblem() {
        ValidationOutcome outcome = validation.validate("", "23.05");

        ValidationOutcome.ConfigInvalid invalid =
                assertInstanceOf(ValidationOutcome.ConfigInvalid.class, outcome);
        assertEquals(ValidationOutcome.ConfigInvalid.EXIT_CODE, invalid.exitCode());
    }

    @Test
    void unknownTokenInFormat_isConfigInvalid_namingTheProblem() {
        ValidationOutcome outcome = validation.validate("YY.NOTATOKEN", "23.1");

        ValidationOutcome.ConfigInvalid invalid =
                assertInstanceOf(ValidationOutcome.ConfigInvalid.class, outcome);
        assertTrue(invalid.message().toLowerCase().contains("format")
                        || invalid.message().contains("NOTATOKEN"),
                "message should name the malformed/unrecognised format content");
    }

    @Test
    void versionDoesNotFitFormat_isConfigInvalid_notCrash() {
        // "not-a-calver-version" doesn't match "YY.0M" at all.
        ValidationOutcome outcome = validation.validate("YY.0M", "not-a-calver-version");

        ValidationOutcome.ConfigInvalid invalid =
                assertInstanceOf(ValidationOutcome.ConfigInvalid.class, outcome);
        assertEquals(ValidationOutcome.ConfigInvalid.EXIT_CODE, invalid.exitCode());
    }

    /**
     * Completeness guard for {@code CalverFormatValidation}'s hand-maintained
     * {@code TYPE_TO_SYMBOL} reverse map, which mirrors {@link CalverFormat}'s own (unexposed)
     * {@code SYMBOL_TO_TYPE} table. Exercises every {@link CalverFormat.TokenType} as a
     * single-token format so that a future token added to {@code CalverFormat} without a
     * corresponding {@code CalverFormatValidation} entry fails here (with a {@code null} symbol
     * or a missing-mapping exception) instead of silently rendering a broken
     * {@link CalverMapping.TokenDisplay} in production.
     */
    @ParameterizedTest
    @MethodSource("everyTokenTypeWithFormatSymbolAndSampleVersion")
    void everyTokenType_resolvesToItsFormatSymbol_reverseMapStaysComplete(
            CalverFormat.TokenType tokenType, String symbol, String sampleVersion) {
        ValidationOutcome outcome = validation.validate(symbol, sampleVersion);

        ValidationOutcome.CalverOk ok = assertInstanceOf(ValidationOutcome.CalverOk.class, outcome,
                "format '" + symbol + "' with version '" + sampleVersion + "' should resolve for token " + tokenType);

        CalverMapping mapping = ok.mapping();
        assertEquals(1, mapping.tokens().size());
        CalverMapping.TokenDisplay display = mapping.tokens().get(0);
        assertEquals(symbol, display.symbol(),
                "TYPE_TO_SYMBOL entry for " + tokenType + " must round-trip to its format-string symbol");
        assertNotNull(display.displayedValue());
    }

    private static Stream<Arguments> everyTokenTypeWithFormatSymbolAndSampleVersion() {
        return Stream.of(
                Arguments.of(CalverFormat.TokenType.YYYY, "YYYY", "2023"),
                Arguments.of(CalverFormat.TokenType.YY, "YY", "23"),
                Arguments.of(CalverFormat.TokenType.ZERO_Y, "0Y", "23"),
                Arguments.of(CalverFormat.TokenType.MM, "MM", "5"),
                Arguments.of(CalverFormat.TokenType.ZERO_M, "0M", "05"),
                Arguments.of(CalverFormat.TokenType.WW, "WW", "5"),
                Arguments.of(CalverFormat.TokenType.ZERO_W, "0W", "05"),
                Arguments.of(CalverFormat.TokenType.DD, "DD", "5"),
                Arguments.of(CalverFormat.TokenType.ZERO_D, "0D", "05"),
                Arguments.of(CalverFormat.TokenType.MAJOR, "MAJOR", "1"),
                Arguments.of(CalverFormat.TokenType.MINOR, "MINOR", "1"),
                Arguments.of(CalverFormat.TokenType.MICRO, "MICRO", "1"),
                Arguments.of(CalverFormat.TokenType.MODIFIER, "MODIFIER", "alpha")
        );
    }
}
