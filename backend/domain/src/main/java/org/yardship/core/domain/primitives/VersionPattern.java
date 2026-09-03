package org.yardship.core.domain.primitives;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A validated regex rule for extracting a raw version token from text: compiles a caller-supplied
 * pattern once at construction, requiring it to compile and to declare at least one capture group
 * (group 1 is what every caller reads), and exposes every match's group 1 as a raw string, in
 * input order.
 *
 * <p>This is the single implementation of the "compile a regex, require capture group 1" rule
 * shared by every caller that extracts a version out of a regex — see
 * {@code docs/adr/0032-config-errors-degrade-per-app-never-the-boot.md} and
 * {@code docs/adr/0030-http-header-current-source.md}, "The first match, not the largest": only
 * the SELECTION rule differs between callers ({@code http-regex} takes the largest, {@code
 * http-header} takes the first, {@code conf-check} reports every candidate); compilation,
 * validation and candidate matching do not, and must not drift into divergent copies.
 *
 * <p>Deliberately has NO {@link VersionParser} dependency: parsing a candidate, selecting among
 * candidates, and reporting per-candidate outcomes all stay with each caller. This class only
 * matches; it never parses or selects.
 *
 * <p>Messages are neutral: they name no source kind, leg, or config field, because this type is
 * shared by callers with different vocabularies for the same rule ({@code "http-regex"} vs
 * {@code "http-header"}, a server factory vs a {@code conf-check} validator). A caller that needs a
 * kind-labelled failure wraps construction and reformats the message itself.
 */
public final class VersionPattern {

    private final Pattern pattern;

    /**
     * @param regex a Java regex, expected to declare at least one capture group.
     * @throws IllegalArgumentException if {@code regex} fails to compile — the
     *                                  {@link PatternSyntaxException} is retained as the cause, and
     *                                  callers rely on that to tell the two failures apart — or if
     *                                  it compiles but declares no capture group 1, which carries
     *                                  no cause.
     */
    public VersionPattern(String regex) {
        Pattern compiled;
        try {
            compiled = Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(
                    "Regex '" + regex + "' does not compile: " + e.getMessage(), e);
        }
        if (compiled.matcher("").groupCount() < 1) {
            throw new IllegalArgumentException(
                    "Regex '" + regex + "' has no capture group 1 to parse a version from.");
        }
        this.pattern = compiled;
    }

    /**
     * Every match's capture group 1 against {@code text}, as raw (unparsed) strings, in input
     * order. Returns an empty list when nothing matches.
     */
    public List<String> rawCandidates(String text) {
        List<String> candidates = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            candidates.add(matcher.group(1));
        }
        return candidates;
    }
}
