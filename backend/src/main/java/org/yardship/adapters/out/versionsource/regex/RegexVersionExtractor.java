package org.yardship.adapters.out.versionsource.regex;

import org.yardship.core.domain.exceptions.InvalidVersionException;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionPattern;
import org.yardship.core.domain.primitives.VersionValue;

import java.util.Optional;
import java.util.regex.PatternSyntaxException;

/**
 * Leg-neutral, shared extraction core factored out of {@code HttpRegexLatestSource}. Delegates
 * pattern compilation and candidate matching to {@link VersionPattern} (validating it compiles and
 * has at least one capture group), and exposes two selection rules over its {@link
 * VersionPattern#rawCandidates}: parse each raw candidate through the app's {@link VersionParser},
 * silently skipping any that fail to parse.
 *
 * <ul>
 *   <li>{@link #largestIn}: the largest parseable candidate — the {@code http-regex} latest-leg
 *       rule (same largest-wins rule as {@code github-release} / ADR-0010 and {@code oci-registry}
 *       / ADR-0014).</li>
 *   <li>{@link #firstIn}: capture group 1 of the FIRST match in input order, even when a later
 *       match parses larger — the current-leg rule, where a current version is a single
 *       observation, not a selection.</li>
 * </ul>
 *
 * <p>Both selection methods return {@link Optional#empty()} rather than throwing when nothing
 * matches or nothing parses; the caller words the kind-appropriate failure. Pattern-validation
 * failures (a non-compiling regex, or one with zero capture groups) THROW at construction, as the
 * {@link IllegalArgumentException} that is this codebase's declared "this config fragment is
 * unusable" signal: the resolver catches it and degrades that one app, never the boot (ADR-0032).
 * {@link VersionPattern}'s own validation messages are neutral (name no source kind or leg); this
 * class reformats them with its {@code sourceLabel}, preserving the kind-labelled wording callers
 * depend on.
 */
public class RegexVersionExtractor {

    private final VersionPattern pattern;
    private final VersionParser parser;

    /**
     * @param sourceLabel names the consuming kind/leg for validation-failure messages, e.g.
     *                    {@code "'http-regex' latest source"} or {@code "'http-header' current
     *                    source"}, so a boot failure reads {@code "The 'http-regex' latest
     *                    source's 'regex' ..."} — mirroring the kind-label precedent in
     *                    {@code HttpTransportConfig}.
     */
    public RegexVersionExtractor(String sourceLabel, String regex, VersionParser parser) {
        this.pattern = compile(sourceLabel, regex);
        this.parser = parser;
    }

    /**
     * The largest parseable candidate across every match, under the app's version scheme.
     */
    public Optional<VersionValue> largestIn(String text) {
        VersionValue largest = null;
        for (String candidate : pattern.rawCandidates(text)) {
            VersionValue parsed = tryParse(candidate);
            if (parsed != null && (largest == null || largest.isOlderThan(parsed))) {
                largest = parsed;
            }
        }
        return Optional.ofNullable(largest);
    }

    /**
     * Capture group 1 of the first match in input order that parses, even when a later match
     * parses to a larger version.
     */
    public Optional<VersionValue> firstIn(String text) {
        for (String candidate : pattern.rawCandidates(text)) {
            VersionValue parsed = tryParse(candidate);
            if (parsed != null) {
                return Optional.of(parsed);
            }
        }
        return Optional.empty();
    }

    private VersionValue tryParse(String token) {
        try {
            return parser.parse(token);
        } catch (InvalidVersionException ex) {
            return null;
        }
    }

    private static VersionPattern compile(String sourceLabel, String regex) {
        try {
            return new VersionPattern(regex);
        } catch (IllegalArgumentException ex) {
            if (ex.getCause() instanceof PatternSyntaxException syntaxEx) {
                throw new IllegalArgumentException(
                        "The " + sourceLabel + "'s 'regex' does not compile: " + syntaxEx.getMessage(), ex);
            }
            throw new IllegalArgumentException(
                    "The " + sourceLabel + "'s 'regex' must have at least one capture group "
                            + "(group 1 is read); was: '" + regex + "'.", ex);
        }
    }
}
