package org.yardship.confcheck.validation;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Shared compile-and-validate rule for every scheme-aware validator that reads a version out of a
 * user-supplied regex: compiles the pattern and requires at least one capture group (group 1 is
 * what every caller reads). Extracted so {@link RegexExtractionValidation} (largest-wins, the
 * {@code http-regex} latest leg) and {@link HeaderExtractionValidation} (first-match-wins, the
 * {@code http-header} current leg) share exactly one copy of this rule rather than each keeping
 * its own — see {@code docs/adr/0030-http-header-current-source.md}, "The first match, not the
 * largest": only the SELECTION rule differs between the two kinds; pattern compilation and
 * capture-group validation do not, and must not drift into two divergent copies.
 */
final class RegexPatternValidation {

    private RegexPatternValidation() {}

    /**
     * @throws InvalidPatternException if {@code regex} fails to compile, or compiles but has no
     *                                  capture group 1.
     */
    static Pattern compileWithCaptureGroup(String regex) {
        Pattern pattern;
        try {
            pattern = Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new InvalidPatternException("Invalid regex '" + regex + "': " + e.getMessage());
        }
        Matcher matcher = pattern.matcher("");
        if (matcher.groupCount() < 1) {
            throw new InvalidPatternException(
                    "Regex '" + regex + "' has no capture group 1 to parse a version from.");
        }
        return pattern;
    }

    /** Raised when a regex fails {@link #compileWithCaptureGroup(String)}'s structural checks. */
    static final class InvalidPatternException extends RuntimeException {
        InvalidPatternException(String message) {
            super(message);
        }
    }
}
