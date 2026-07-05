package org.yardship.unit.core.domain.primitives;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.yardship.core.domain.primitives.CalverFormat;
import org.yardship.core.domain.primitives.CalverVersion;
import org.yardship.core.domain.primitives.VersionValue;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CalverFormat}: the calver.org format parser.
 *
 * <p>Covers the full token vocabulary (padded and unpadded variants), literal separators,
 * per-token {@link VersionValue.Diff} category mapping, and rejection of unknown tokens /
 * malformed format strings.
 *
 * <p>Assumed API surface for the implementer:
 * <ul>
 *   <li>{@code CalverFormat(String format)} — constructor; throws {@link IllegalArgumentException}
 *       for null, blank, no-token, or unknown-token format strings.</li>
 *   <li>{@code List<CalverFormat.TokenType> tokens()} — ordered list of parsed token types
 *       (literal separators are NOT included in this list).</li>
 *   <li>{@code enum CalverFormat.TokenType} — one constant per calver.org token, with method
 *       {@code VersionValue.Diff diffCategory()}. Because Java enum names cannot start with a
 *       digit, the padded variants use {@code ZERO_Y}, {@code ZERO_M}, {@code ZERO_W},
 *       {@code ZERO_D} in Java, matching the calver.org symbols {@code 0Y}, {@code 0M},
 *       {@code 0W}, {@code 0D} in the format string.</li>
 * </ul>
 *
 * <p>This is a pure domain unit test — no Quarkus context needed.
 */
public class CalverFormatTests {

    // -----------------------------------------------------------------------
    // Recognises every calver.org token individually
    // -----------------------------------------------------------------------

    @ParameterizedTest(name = "token ''{0}'' is a valid single-token format")
    @MethodSource("allTokenSymbols")
    void CalverFormat_recognizesEachCalverToken_asASingleTokenFormat(String symbol) {
        // A format string containing only that token symbol must parse without error.
        assertDoesNotThrow(() -> new CalverFormat(symbol),
                "Expected token '" + symbol + "' to be recognized by CalverFormat");
    }

    // -----------------------------------------------------------------------
    // Compound format tokenisation
    // -----------------------------------------------------------------------

    @Test
    void CalverFormat_parsesCompoundFormat_intoOrderedTokenList() {
        // Arrange & Act
        CalverFormat format = new CalverFormat("YYYY.0M.DD");

        // Assert
        List<CalverFormat.TokenType> tokens = format.tokens();
        assertEquals(3, tokens.size());
        assertEquals(CalverFormat.TokenType.YYYY,   tokens.get(0));
        assertEquals(CalverFormat.TokenType.ZERO_M, tokens.get(1));
        assertEquals(CalverFormat.TokenType.DD,     tokens.get(2));
    }

    @Test
    void CalverFormat_parsesAllDateTokenVariants_inOrder() {
        // All unpadded and padded year/month/week/day tokens in one format.
        CalverFormat format = new CalverFormat("YYYY.YY.0Y.MM.0M.WW.0W.DD.0D");

        List<CalverFormat.TokenType> tokens = format.tokens();
        assertEquals(9, tokens.size());
        assertEquals(CalverFormat.TokenType.YYYY,   tokens.get(0));
        assertEquals(CalverFormat.TokenType.YY,     tokens.get(1));
        assertEquals(CalverFormat.TokenType.ZERO_Y, tokens.get(2));
        assertEquals(CalverFormat.TokenType.MM,     tokens.get(3));
        assertEquals(CalverFormat.TokenType.ZERO_M, tokens.get(4));
        assertEquals(CalverFormat.TokenType.WW,     tokens.get(5));
        assertEquals(CalverFormat.TokenType.ZERO_W, tokens.get(6));
        assertEquals(CalverFormat.TokenType.DD,     tokens.get(7));
        assertEquals(CalverFormat.TokenType.ZERO_D, tokens.get(8));
    }

    @Test
    void CalverFormat_parsesVersionTokens_inOrder() {
        // MAJOR / MINOR / MICRO / MODIFIER are all valid calver.org version tokens.
        CalverFormat format = new CalverFormat("MAJOR.MINOR.MICRO.MODIFIER");

        List<CalverFormat.TokenType> tokens = format.tokens();
        assertEquals(4, tokens.size());
        assertEquals(CalverFormat.TokenType.MAJOR,    tokens.get(0));
        assertEquals(CalverFormat.TokenType.MINOR,    tokens.get(1));
        assertEquals(CalverFormat.TokenType.MICRO,    tokens.get(2));
        assertEquals(CalverFormat.TokenType.MODIFIER, tokens.get(3));
    }

    @Test
    void CalverFormat_toleratesHyphenSeparator_betweenTokens() {
        // Hyphens are valid literal separators; only tokens are returned by tokens().
        CalverFormat format = new CalverFormat("YYYY-0M-DD");

        List<CalverFormat.TokenType> tokens = format.tokens();
        assertEquals(3, tokens.size());
        assertEquals(CalverFormat.TokenType.YYYY,   tokens.get(0));
        assertEquals(CalverFormat.TokenType.ZERO_M, tokens.get(1));
        assertEquals(CalverFormat.TokenType.DD,     tokens.get(2));
    }

    @Test
    void CalverFormat_toleratesUnderscoreSeparator_betweenTokens() {
        CalverFormat format = new CalverFormat("YYYY_0M");

        List<CalverFormat.TokenType> tokens = format.tokens();
        assertEquals(2, tokens.size());
        assertEquals(CalverFormat.TokenType.YYYY,   tokens.get(0));
        assertEquals(CalverFormat.TokenType.ZERO_M, tokens.get(1));
    }

    // -----------------------------------------------------------------------
    // Per-token Diff category mapping
    // -----------------------------------------------------------------------

    @Test
    void diffCategory_yearTokens_mapToMajor() {
        assertEquals(VersionValue.Diff.MAJOR, CalverFormat.TokenType.YYYY.diffCategory());
        assertEquals(VersionValue.Diff.MAJOR, CalverFormat.TokenType.YY.diffCategory());
        assertEquals(VersionValue.Diff.MAJOR, CalverFormat.TokenType.ZERO_Y.diffCategory());
    }

    @Test
    void diffCategory_majorVersionToken_mapsToDiffMajor() {
        // The embedded MAJOR token (not a calendar field) also maps to Diff.MAJOR.
        assertEquals(VersionValue.Diff.MAJOR, CalverFormat.TokenType.MAJOR.diffCategory());
    }

    @Test
    void diffCategory_monthTokens_mapToMinor() {
        assertEquals(VersionValue.Diff.MINOR, CalverFormat.TokenType.MM.diffCategory());
        assertEquals(VersionValue.Diff.MINOR, CalverFormat.TokenType.ZERO_M.diffCategory());
    }

    @Test
    void diffCategory_weekTokens_mapToMinor() {
        assertEquals(VersionValue.Diff.MINOR, CalverFormat.TokenType.WW.diffCategory());
        assertEquals(VersionValue.Diff.MINOR, CalverFormat.TokenType.ZERO_W.diffCategory());
    }

    @Test
    void diffCategory_dayTokens_mapToMinor() {
        assertEquals(VersionValue.Diff.MINOR, CalverFormat.TokenType.DD.diffCategory());
        assertEquals(VersionValue.Diff.MINOR, CalverFormat.TokenType.ZERO_D.diffCategory());
    }

    @Test
    void diffCategory_minorVersionToken_mapsToDiffMinor() {
        // The embedded MINOR token maps to Diff.MINOR.
        assertEquals(VersionValue.Diff.MINOR, CalverFormat.TokenType.MINOR.diffCategory());
    }

    @Test
    void diffCategory_microAndModifier_mapToPatch() {
        assertEquals(VersionValue.Diff.PATCH, CalverFormat.TokenType.MICRO.diffCategory());
        assertEquals(VersionValue.Diff.PATCH, CalverFormat.TokenType.MODIFIER.diffCategory());
    }

    // -----------------------------------------------------------------------
    // Rejection of unknown tokens and malformed formats
    // -----------------------------------------------------------------------

    @Test
    void CalverFormat_throwsIllegalArgumentException_forUnknownToken() {
        // "FOOBAR" is not in the calver.org vocabulary.
        assertThrows(IllegalArgumentException.class,
                () -> new CalverFormat("YYYY.FOOBAR"),
                "An unknown token must cause CalverFormat construction to fail");
    }

    @Test
    void CalverFormat_throwsIllegalArgumentException_forPartiallyMatchingUnknownToken() {
        // "YEARS" looks calver-ish but is not a recognised token.
        assertThrows(IllegalArgumentException.class,
                () -> new CalverFormat("YEARS.0M"),
                "A token-like but unrecognised symbol must be rejected");
    }

    @Test
    void CalverFormat_throwsIllegalArgumentException_forBlankFormat() {
        assertThrows(IllegalArgumentException.class,
                () -> new CalverFormat(""),
                "A blank format string must be rejected");
    }

    @Test
    void CalverFormat_throwsIllegalArgumentException_forNullFormat() {
        assertThrows(IllegalArgumentException.class,
                () -> new CalverFormat(null),
                "A null format string must be rejected");
    }

    @Test
    void CalverFormat_throwsIllegalArgumentException_whenFormatContainsNoTokens() {
        // A string composed entirely of separators and no recognised tokens is malformed.
        assertThrows(IllegalArgumentException.class,
                () -> new CalverFormat("..."),
                "A format string with no tokens must be rejected");
    }

    // -----------------------------------------------------------------------
    // formatString() — retains the exact original format string
    // -----------------------------------------------------------------------

    @Test
    void formatString_returnsExactlyTheStringPassedToTheConstructor() {
        // Arrange
        String original = "YY.0M.MICRO";
        CalverFormat format = new CalverFormat(original);

        // Act & Assert
        assertEquals(original, format.formatString(),
                "formatString() must return exactly the string the format was constructed from");
    }

    @Test
    void formatString_isUnmodified_evenWithHyphenSeparators() {
        // Round-tripping must not normalise separators (e.g. hyphen to dot).
        String original = "YYYY-0M-DD";
        CalverFormat format = new CalverFormat(original);

        assertEquals(original, format.formatString());
    }

    @Test
    void reconstructedCalverFormat_parsesAndComparesIdentically_toOriginal() {
        // Arrange: a CalverFormat reconstructed from formatString() must be behaviorally
        // equivalent to the original — same tokens() and same tryParse() shape.
        CalverFormat original = new CalverFormat("YY.0M.MICRO");

        // Act
        CalverFormat reconstructed = new CalverFormat(original.formatString());

        // Assert — same ordered tokens
        assertEquals(original.tokens(), reconstructed.tokens(),
                "Reconstructed CalverFormat must parse to the same token list as the original");

        // Assert — parses a representative version string identically (via CalverVersion, since
        // tryParse() is not part of CalverFormat's public surface).
        CalverVersion viaOriginal = new CalverVersion("24.04.5", original);
        CalverVersion viaReconstructed = new CalverVersion("24.04.5", reconstructed);

        assertEquals(viaOriginal.value(), viaReconstructed.value());
        assertFalse(viaOriginal.isOlderThan(viaReconstructed));
        assertFalse(viaReconstructed.isOlderThan(viaOriginal));
        assertEquals(VersionValue.Diff.NONE, viaOriginal.diff(viaReconstructed));
    }

    // -----------------------------------------------------------------------
    // Provider
    // -----------------------------------------------------------------------

    private static Stream<String> allTokenSymbols() {
        // Full calver.org vocabulary as they appear in format strings (not Java enum names).
        return Stream.of(
                "YYYY", "YY", "0Y",
                "MM",   "0M",
                "WW",   "0W",
                "DD",   "0D",
                "MAJOR", "MINOR", "MICRO", "MODIFIER"
        );
    }
}
