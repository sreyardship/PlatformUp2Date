package org.yardship.core.domain.primitives;

import org.yardship.core.domain.exceptions.InvalidVersionException;

import java.util.Objects;

/**
 * Calendar-versioning backed {@link VersionValue}. Holds the parsed, ordered components of a version
 * string against a {@link CalverFormat}, plus the <b>original string for display</b> — {@link #value()}
 * returns it verbatim so {@code 24.04} stays {@code 24.04}.
 *
 * <p>Ordering is positional-numeric in token order. The {@code MODIFIER} token (the only non-numeric
 * token) orders prerelease-style: a build carrying a modifier sorts BELOW the same calendar date
 * without one (e.g. {@code 24.04.alpha < 24.04}). Drift severity is graded by token category — the
 * most-significant differing token's category wins (see {@link CalverFormat.TokenType#diffCategory()}).
 *
 * <p>Like {@link SemverVersion}, a {@code CalverVersion} only compares with another
 * {@code CalverVersion}; a foreign {@link VersionValue} throws {@link IllegalArgumentException}. The
 * single per-app {@link VersionParser} guarantees both legs share a scheme, so this can't occur in
 * production — the guard exists to keep the sealed-interface contract honest.
 */
public final class CalverVersion implements VersionValue {

    private final String original;
    private final CalverFormat format;
    private final int[] numericValues;
    private final String modifier; // null when absent
    private final String[] rawGroups; // displayed substring per token, in format.tokens() order; null if absent

    /**
     * Parses {@code original} against {@code format}.
     *
     * @throws InvalidVersionException if {@code original} is null or does not match {@code format}
     *                                 (including a non-numeric value for a numeric token). Missing
     *                                 TRAILING tokens default to {@code 0} (numeric) or absent
     *                                 (MODIFIER), enabling length-differing comparisons.
     */
    public CalverVersion(String original, CalverFormat format) {
        if (original == null) {
            throw new InvalidVersionException("Calver version string cannot be null");
        }
        CalverFormat.ParsedComponents parsed = format.tryParse(original);
        if (parsed == null) {
            throw new InvalidVersionException(
                    "Unable to parse calver version '" + original + "' against its format");
        }
        this.original = original;
        this.format = format;
        this.numericValues = parsed.numericValues();
        this.modifier = parsed.modifier();
        this.rawGroups = parsed.rawGroups();
    }

    private CalverVersion(
            String original, CalverFormat format, int[] numericValues, String modifier, String[] rawGroups) {
        this.original = original;
        this.format = format;
        this.numericValues = numericValues;
        this.modifier = modifier;
        this.rawGroups = rawGroups;
    }

    @Override
    public boolean isOlderThan(VersionValue comparable) {
        return compareTo(castToCalverVersion(comparable)) < 0;
    }

    @Override
    public VersionValue.Diff diff(VersionValue other) {
        CalverVersion that = castToCalverVersion(other);
        Diff result = Diff.NONE;
        for (int i = 0; i < format.tokens().size(); i++) {
            if (differsAt(that, i) && format.tokens().get(i).diffCategory().isAtLeast(result)) {
                result = format.tokens().get(i).diffCategory();
            }
        }
        return result;
    }

    @Override
    public String value() {
        return original;
    }

    @Override
    public VersionScheme scheme() {
        return VersionScheme.CALVER;
    }

    /** The {@link CalverFormat} this version was parsed against. */
    public CalverFormat calverFormat() {
        return format;
    }

    @Override
    public java.util.Optional<String> preReleaseSegment() {
        // Calver's MODIFIER token is the closest analogue of a semver prerelease segment.
        return java.util.Optional.ofNullable(modifier);
    }

    /**
     * Returns a new {@link CalverVersion} with the {@code MODIFIER} segment (and its preceding
     * separator) stripped from the original string, when the format has a {@code MODIFIER} token and
     * this version carries one. Otherwise returns an equivalent version with an unchanged
     * {@link #value()}. The original instance is never mutated.
     */
    @Override
    public VersionValue withoutPreRelease() {
        int modifierIndex = format.modifierIndex();
        if (modifierIndex < 0 || modifier == null) {
            return this;
        }
        String separator = format.separators().get(modifierIndex);
        String suffix = separator + modifier;
        String stripped = original.endsWith(suffix)
                ? original.substring(0, original.length() - suffix.length())
                : original.replace(suffix, "");
        int[] clearedNumerics = numericValues.clone();
        String[] clearedRawGroups = rawGroups.clone();
        clearedRawGroups[modifierIndex] = null;
        return new CalverVersion(stripped, format, clearedNumerics, null, clearedRawGroups);
    }

    /**
     * The displayed substring (zero-padding preserved) this version carries for {@code type}, or
     * {@code null} if {@code type} is not one of {@link #format}'s declared tokens, or is a trailing
     * token absent from {@link #original}. Package-private: consumed by {@link ChangelogTemplate}.
     */
    String displayedValue(CalverFormat.TokenType type) {
        int index = format.tokens().indexOf(type);
        if (index < 0) {
            return null;
        }
        return rawGroups[index];
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CalverVersion that)) {
            return false;
        }
        return original.equals(that.original) && format == that.format;
    }

    @Override
    public int hashCode() {
        return Objects.hash(original, format);
    }

    @Override
    public String toString() {
        return original;
    }

    /**
     * Positional comparison in token order. Numeric tokens compare numerically; the {@code MODIFIER}
     * token orders prerelease-style (absent ranks above present; two present compare lexicographically).
     * The first differing token decides — so calendar fields, declared before any modifier, take
     * precedence.
     */
    private int compareTo(CalverVersion that) {
        int modifierIndex = format.modifierIndex();
        for (int i = 0; i < format.tokens().size(); i++) {
            if (i == modifierIndex) {
                int c = compareModifier(this.modifier, that.modifier);
                if (c != 0) {
                    return c;
                }
            } else {
                int c = Integer.compare(this.numericValues[i], that.numericValues[i]);
                if (c != 0) {
                    return c;
                }
            }
        }
        return 0;
    }

    // Prerelease-style: a present modifier sorts BELOW an absent one (a build with a modifier is
    // "less than" the same calendar date without). Two present modifiers compare lexicographically.
    private static int compareModifier(String a, String b) {
        if (Objects.equals(a, b)) {
            return 0;
        }
        if (a == null) {
            return 1; // this has no modifier → greater (stable outranks prerelease)
        }
        if (b == null) {
            return -1; // this has a modifier → lesser
        }
        return a.compareTo(b);
    }

    private boolean differsAt(CalverVersion that, int index) {
        if (index == format.modifierIndex()) {
            return !Objects.equals(this.modifier, that.modifier);
        }
        return this.numericValues[index] != that.numericValues[index];
    }

    private static CalverVersion castToCalverVersion(VersionValue v) {
        if (!(v instanceof CalverVersion cv)) {
            throw new IllegalArgumentException(
                    "CalverVersion can only be compared with another CalverVersion, got: "
                            + v.getClass().getSimpleName());
        }
        return cv;
    }
}
