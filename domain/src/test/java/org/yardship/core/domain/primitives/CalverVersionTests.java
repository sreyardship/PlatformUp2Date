package org.yardship.core.domain.primitives;

import org.junit.jupiter.api.Test;
import org.yardship.core.domain.exceptions.InvalidVersionException;
import org.yardship.core.domain.primitives.CalverFormat;
import org.yardship.core.domain.primitives.CalverVersion;
import org.yardship.core.domain.primitives.SemverVersion;
import org.yardship.core.domain.primitives.VersionScheme;
import org.yardship.core.domain.primitives.VersionValue;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behavior suite for {@link CalverVersion}, the calendar-versioning backed {@link VersionValue}.
 *
 * <p>Covers display fidelity ({@code value()} verbatim), ordering (year, month, numeric
 * MICRO, three-digit short years, MODIFIER prerelease-style), drift-category grading
 * (most-significant-category-wins, date-only format never yields PATCH), and
 * {@link VersionValue#withoutPreRelease()} contract.
 *
 * <p>Assumed API surface for the implementer:
 * <ul>
 *   <li>{@code CalverVersion(String original, CalverFormat format)} — constructor; throws
 *       {@link InvalidVersionException} if {@code original} is null or does not match
 *       {@code format}. Missing TRAILING tokens default to 0 (numeric) or absent (MODIFIER),
 *       enabling length-differing comparisons (e.g. {@code "23.05"} against format
 *       {@code "YY.0M.MICRO"} is valid and treats MICRO as 0).</li>
 *   <li>{@code String value()} — returns the original string exactly as provided.</li>
 *   <li>{@code boolean isOlderThan(VersionValue)} — positional-numeric comparison in token
 *       order; MODIFIER (the only non-numeric token) sorts prerelease-style: a version WITH
 *       a MODIFIER is LOWER than the same calendar date WITHOUT.</li>
 *   <li>{@code VersionValue.Diff diff(VersionValue)} — most-significant differing token
 *       category wins; {@link VersionValue.Diff#NONE} when equal.</li>
 *   <li>{@code VersionValue withoutPreRelease()} — if the format has a MODIFIER token and
 *       this version carries a MODIFIER value, returns a new {@code CalverVersion} whose
 *       {@code value()} is the original string with the MODIFIER segment and its preceding
 *       separator stripped. If the format has no MODIFIER, or the MODIFIER is already absent
 *       in this version, returns an equivalent version unchanged (same {@code value()}).</li>
 * </ul>
 *
 * <p>Cross-scheme comparison (CalverVersion vs. SemverVersion) throws
 * {@link IllegalArgumentException} — the single shared parser guarantees this cannot occur
 * in production, but the guard is required by the {@link VersionValue} contract.
 *
 * <p>This is a pure domain unit test — no Quarkus context needed.
 */
public class CalverVersionTests {

    // -----------------------------------------------------------------------
    // Display fidelity
    // -----------------------------------------------------------------------

    @Test
    void value_returnsOriginalStringVerbatim() {
        // Arrange
        CalverFormat format = new CalverFormat("YY.0M");
        CalverVersion version = new CalverVersion("24.04", format);

        // Act & Assert
        assertEquals("24.04", version.value(),
                "value() must return the original string exactly — no normalisation or re-formatting");
    }

    @Test
    void value_preservesLeadingZeroInPaddedToken() {
        // "04" must not be stripped to "4"
        CalverFormat format = new CalverFormat("YYYY.0M");
        CalverVersion version = new CalverVersion("2024.04", format);

        assertEquals("2024.04", version.value());
    }

    // -----------------------------------------------------------------------
    // Ordering — same year, differing month
    // -----------------------------------------------------------------------

    @Test
    void isOlderThan_returnsTrue_whenMonthIsLower_sameYear() {
        // 24.04 < 24.10 (same short year, higher month)
        CalverFormat format = new CalverFormat("YY.0M");
        CalverVersion older = new CalverVersion("24.04", format);
        CalverVersion newer = new CalverVersion("24.10", format);

        assertTrue(older.isOlderThan(newer),  "24.04 must be older than 24.10");
        assertFalse(newer.isOlderThan(older), "24.10 must NOT be older than 24.04");
    }

    @Test
    void isOlderThan_returnsFalse_whenVersionsAreEqual() {
        CalverFormat format = new CalverFormat("YY.0M");
        CalverVersion a = new CalverVersion("24.04", format);
        CalverVersion b = new CalverVersion("24.04", format);

        assertFalse(a.isOlderThan(b), "Equal versions must not be considered older");
        assertFalse(b.isOlderThan(a));
    }

    // -----------------------------------------------------------------------
    // Ordering — length-differing: missing trailing component defaults to 0
    // -----------------------------------------------------------------------

    @Test
    void isOlderThan_lengthDiffering_treatsAbsentTrailingMicroAsZero() {
        // 23.05 < 23.05.5 — MICRO absent defaults to 0, which is less than 5.
        CalverFormat format = new CalverFormat("YY.0M.MICRO");
        CalverVersion shorter = new CalverVersion("23.05",   format); // MICRO absent → 0
        CalverVersion longer  = new CalverVersion("23.05.5", format); // MICRO present → 5

        assertTrue(shorter.isOlderThan(longer),
                "23.05 (MICRO=0 implicit) must be older than 23.05.5 (MICRO=5)");
        assertFalse(longer.isOlderThan(shorter));
    }

    // -----------------------------------------------------------------------
    // Ordering — three-digit short year (numeric, not lexicographic)
    // -----------------------------------------------------------------------

    @Test
    void isOlderThan_threeDigitShortYear_isComparedNumerically() {
        // YY=106 (year 2106) must sort above YY=24 (year 2024).
        // Lexicographic comparison of "24" vs "106" would give "24" > "106" — test that
        // the implementation uses numeric comparison.
        CalverFormat format = new CalverFormat("YY.0M");
        CalverVersion year24  = new CalverVersion("24.01",  format);
        CalverVersion year106 = new CalverVersion("106.01", format);

        assertTrue(year24.isOlderThan(year106),
                "YY=106 must sort ABOVE YY=24 — numeric, not lexicographic comparison");
        assertFalse(year106.isOlderThan(year24));
    }

    // -----------------------------------------------------------------------
    // Ordering — MODIFIER prerelease-style (WITH modifier < WITHOUT)
    // -----------------------------------------------------------------------

    @Test
    void isOlderThan_modifier_sortsPreReleaseStyleBelowVersionWithoutModifier() {
        // A version WITH a MODIFIER sorts BELOW the same calendar date WITHOUT one.
        CalverFormat format = new CalverFormat("YY.0M.MODIFIER");
        CalverVersion withModifier = new CalverVersion("24.04.alpha", format);
        CalverVersion stable       = new CalverVersion("24.04",       format); // MODIFIER absent

        assertTrue(withModifier.isOlderThan(stable),
                "24.04.alpha must sort below stable 24.04 (MODIFIER prerelease semantics)");
        assertFalse(stable.isOlderThan(withModifier));
    }

    @Test
    void isOlderThan_twoModifierVersions_areOrderedLexicographically() {
        // When both sides have a MODIFIER, the earlier calendar date is still older.
        CalverFormat format = new CalverFormat("YY.0M.MODIFIER");
        CalverVersion older = new CalverVersion("24.04.alpha", format);
        CalverVersion newer = new CalverVersion("24.10.beta",  format);

        assertTrue(older.isOlderThan(newer),
                "24.04.alpha must still be older than 24.10.beta (calendar date takes precedence)");
    }

    // -----------------------------------------------------------------------
    // diff — token-category grading
    // -----------------------------------------------------------------------

    @Test
    void diff_isNone_whenVersionsAreEqual() {
        CalverFormat format = new CalverFormat("YYYY.0M");
        CalverVersion a = new CalverVersion("2024.04", format);
        CalverVersion b = new CalverVersion("2024.04", format);

        assertEquals(VersionValue.Diff.NONE, a.diff(b));
    }

    @Test
    void diff_isMajor_whenYearTokenDiffers() {
        CalverFormat format = new CalverFormat("YYYY.0M");
        CalverVersion older = new CalverVersion("2024.04", format);
        CalverVersion newer = new CalverVersion("2025.04", format);

        assertEquals(VersionValue.Diff.MAJOR, older.diff(newer));
    }

    @Test
    void diff_isMinor_whenMonthTokenDiffers_andYearIsSame() {
        CalverFormat format = new CalverFormat("YYYY.0M");
        CalverVersion older = new CalverVersion("2024.04", format);
        CalverVersion newer = new CalverVersion("2024.10", format);

        assertEquals(VersionValue.Diff.MINOR, older.diff(newer));
    }

    @Test
    void diff_isPatch_whenMicroTokenDiffers() {
        CalverFormat format = new CalverFormat("YYYY.0M.MICRO");
        CalverVersion older = new CalverVersion("2024.04.1", format);
        CalverVersion newer = new CalverVersion("2024.04.2", format);

        assertEquals(VersionValue.Diff.PATCH, older.diff(newer));
    }

    @Test
    void diff_isMajor_whenMajorTokenDiffers() {
        // The embedded MAJOR token maps to Diff.MAJOR.
        CalverFormat format = new CalverFormat("YYYY.MAJOR.MINOR");
        CalverVersion older = new CalverVersion("2024.1.0", format);
        CalverVersion newer = new CalverVersion("2024.2.0", format);

        assertEquals(VersionValue.Diff.MAJOR, older.diff(newer));
    }

    @Test
    void diff_isMinor_whenMinorTokenDiffers_andMajorIsSame() {
        // The embedded MINOR token maps to Diff.MINOR.
        CalverFormat format = new CalverFormat("YYYY.MAJOR.MINOR");
        CalverVersion older = new CalverVersion("2024.1.0", format);
        CalverVersion newer = new CalverVersion("2024.1.1", format);

        assertEquals(VersionValue.Diff.MINOR, older.diff(newer));
    }

    @Test
    void diff_mostSignificantCategory_wins_whenMultipleTokensDiffer() {
        // Year AND month AND MICRO all differ → MAJOR must win.
        CalverFormat format = new CalverFormat("YYYY.0M.MICRO");
        CalverVersion older = new CalverVersion("2024.04.1", format);
        CalverVersion newer = new CalverVersion("2025.05.2", format);

        assertEquals(VersionValue.Diff.MAJOR, older.diff(newer),
                "Most-significant-category-wins: YYYY differs → result must be MAJOR");
    }

    @Test
    void diff_dateOnlyFormat_neverYieldsPatch() {
        // A format with only date tokens (YYYY, 0M) contains no MICRO/MODIFIER → cannot PATCH.
        CalverFormat format = new CalverFormat("YYYY.0M");
        CalverVersion older = new CalverVersion("2024.04", format);
        CalverVersion newer = new CalverVersion("2024.10", format);

        VersionValue.Diff diff = older.diff(newer);

        assertNotEquals(VersionValue.Diff.PATCH, diff,
                "A date-only format must never produce PATCH drift");
    }

    // -----------------------------------------------------------------------
    // withoutPreRelease contract
    // -----------------------------------------------------------------------

    /**
     * Contract: if the format has a MODIFIER token and this version carries a MODIFIER value,
     * {@code withoutPreRelease()} returns a new {@link CalverVersion} whose {@code value()} is
     * the original string with the MODIFIER segment and its preceding separator removed.
     *
     * <p>If the format has no MODIFIER, or the MODIFIER is absent in this version, returns an
     * equivalent version (same {@code value()}).
     */
    @Test
    void withoutPreRelease_stripsModifierSegment_whenFormatHasModifierAndVersionCarriesIt() {
        CalverFormat format = new CalverFormat("YYYY.0M.MODIFIER");
        CalverVersion withModifier = new CalverVersion("2024.04.alpha", format);

        VersionValue stripped = withModifier.withoutPreRelease();

        assertEquals("2024.04", stripped.value(),
                "withoutPreRelease() must strip the MODIFIER segment and its preceding separator");
    }

    @Test
    void withoutPreRelease_returnsEquivalentVersion_whenFormatHasNoModifier() {
        CalverFormat format = new CalverFormat("YYYY.0M.MICRO");
        CalverVersion version = new CalverVersion("2024.04.1", format);

        VersionValue result = version.withoutPreRelease();

        assertEquals("2024.04.1", result.value(),
                "withoutPreRelease() on a format without MODIFIER must return a version with the same value");
    }

    @Test
    void withoutPreRelease_returnsEquivalentVersion_whenModifierIsAlreadyAbsent() {
        // Format has MODIFIER token but this version was created without one.
        CalverFormat format = new CalverFormat("YYYY.0M.MODIFIER");
        CalverVersion stable = new CalverVersion("2024.04", format); // MODIFIER absent

        VersionValue result = stable.withoutPreRelease();

        assertEquals("2024.04", result.value(),
                "withoutPreRelease() on a version already lacking MODIFIER must be a no-op");
    }

    @Test
    void withoutPreRelease_doesNotMutateTheOriginal() {
        CalverFormat format = new CalverFormat("YYYY.0M.MODIFIER");
        CalverVersion withModifier = new CalverVersion("2024.04.alpha", format);

        withModifier.withoutPreRelease(); // discard result

        assertEquals("2024.04.alpha", withModifier.value(),
                "CalverVersion is immutable: withoutPreRelease() must not alter the original instance");
    }

    // -----------------------------------------------------------------------
    // Parsing failures — non-matching raw string
    // -----------------------------------------------------------------------

    @Test
    void CalverVersion_throwsInvalidVersionException_whenRawStringDoesNotMatchFormat() {
        CalverFormat format = new CalverFormat("YYYY.0M");

        assertThrows(InvalidVersionException.class,
                () -> new CalverVersion("not-a-date", format),
                "A raw string that does not match the format must throw InvalidVersionException");
    }

    @Test
    void CalverVersion_throwsInvalidVersionException_whenRawStringIsNull() {
        CalverFormat format = new CalverFormat("YYYY.0M");

        assertThrows(InvalidVersionException.class,
                () -> new CalverVersion(null, format),
                "A null raw string must throw InvalidVersionException");
    }

    @Test
    void CalverVersion_throwsInvalidVersionException_whenNonNumericValueForNumericToken() {
        // "2024.XX" — 0M must be a number.
        CalverFormat format = new CalverFormat("YYYY.0M");

        assertThrows(InvalidVersionException.class,
                () -> new CalverVersion("2024.XX", format),
                "A non-numeric component for a numeric token must throw InvalidVersionException");
    }

    // -----------------------------------------------------------------------
    // scheme() self-description
    // -----------------------------------------------------------------------

    @Test
    void scheme_returnsCalver_forAnyCalverVersion() {
        // A VersionValue must self-report the scheme it was built under, so an adapter holding
        // just the instance (no live app config) can tell SEMVER from CALVER.
        CalverFormat format = new CalverFormat("YY.0M");
        CalverVersion version = new CalverVersion("24.04", format);

        assertEquals(VersionScheme.CALVER, version.scheme(),
                "CalverVersion.scheme() must always return VersionScheme.CALVER");
    }

    // -----------------------------------------------------------------------
    // calverFormat() accessor — recovers the format used to parse this version
    // -----------------------------------------------------------------------

    @Test
    void calverFormat_returnsTheFormatTheVersionWasConstructedWith() {
        // Arrange
        CalverFormat format = new CalverFormat("YY.0M.MICRO");
        CalverVersion version = new CalverVersion("24.04.5", format);

        // Act
        CalverFormat recovered = version.calverFormat();

        // Assert — same instance, or at minimum an equivalent one (same original format string).
        assertEquals(format.formatString(), recovered.formatString(),
                "calverFormat() must return the same (or an equivalent) CalverFormat the version was constructed with");
        assertEquals(format.tokens(), recovered.tokens());
    }

    // -----------------------------------------------------------------------
    // Cross-type comparison guard
    // -----------------------------------------------------------------------

    @Test
    void isOlderThan_throwsIllegalArgumentException_whenComparedToNonCalverVersion() {
        // A single shared VersionParser guarantees this cannot occur in production,
        // but the guard must still exist and throw clearly.
        CalverFormat format = new CalverFormat("YYYY.0M");
        CalverVersion calver = new CalverVersion("2024.04", format);
        SemverVersion semver = new SemverVersion("1.0.0");

        assertThrows(IllegalArgumentException.class,
                () -> calver.isOlderThan(semver),
                "CalverVersion must reject comparison with a non-CalverVersion VersionValue");
    }

    @Test
    void diff_throwsIllegalArgumentException_whenComparedToNonCalverVersion() {
        CalverFormat format = new CalverFormat("YYYY.0M");
        CalverVersion calver = new CalverVersion("2024.04", format);
        SemverVersion semver = new SemverVersion("1.0.0");

        assertThrows(IllegalArgumentException.class,
                () -> calver.diff(semver),
                "CalverVersion.diff() must reject comparison with a non-CalverVersion VersionValue");
    }
}
