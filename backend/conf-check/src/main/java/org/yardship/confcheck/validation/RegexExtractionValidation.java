package org.yardship.confcheck.validation;

import org.yardship.confcheck.outcome.RegexCandidate;
import org.yardship.confcheck.outcome.ValidationOutcome;
import org.yardship.core.domain.exceptions.InvalidVersionException;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionPattern;
import org.yardship.core.domain.primitives.VersionValue;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates an {@code http-regex} {@code regex} against a body: compiles the regex, finds every
 * match, parses capture group 1 of each through the supplied {@link VersionParser}, and reports
 * every candidate, marking the largest parseable as the winner.
 *
 * <p>This transparently reimplements the "largest wins" loop from the production
 * {@code HttpRegexLatestSource} (backend) rather than calling its opaque {@code version()} method,
 * so it can report every candidate instead of only the winner or a single failure.
 *
 * <ul>
 *   <li>Regex fails to compile ({@link java.util.regex.PatternSyntaxException}), or has no capture
 *       group 1 → {@link ValidationOutcome.ConfigInvalid}.</li>
 *   <li>Zero matches, or every match's group 1 fails to parse → {@link ValidationOutcome.ValidButEmpty}.</li>
 *   <li>At least one match parses → {@link ValidationOutcome.Ok}, winner = the largest parseable
 *       candidate per {@link org.yardship.core.domain.primitives.VersionValue#isOlderThan}.</li>
 * </ul>
 */
public final class RegexExtractionValidation {

    /**
     * @param body  the fetched/read body to search.
     * @param regex a Java regex with at least one capture group; group 1 is parsed per candidate.
     * @param parser the scheme-configured parser (see {@link org.yardship.confcheck.version.VersionSpec}).
     */
    public ValidationOutcome validate(String body, String regex, VersionParser parser) {
        VersionPattern pattern;
        try {
            pattern = new VersionPattern(regex);
        } catch (IllegalArgumentException e) {
            return new ValidationOutcome.ConfigInvalid(e.getMessage());
        }

        List<RegexCandidate> candidates = new ArrayList<>();
        RegexCandidate winner = null;
        for (String rawText : pattern.rawCandidates(body)) {
            RegexCandidate candidate = toCandidate(rawText, parser);
            candidates.add(candidate);
            if (candidate.isParsed()
                    && (winner == null || winner.parsed().get().isOlderThan(candidate.parsed().get()))) {
                winner = candidate;
            }
        }

        if (winner == null) {
            return new ValidationOutcome.ValidButEmpty(candidates);
        }
        return new ValidationOutcome.Ok(candidates, winner);
    }

    private RegexCandidate toCandidate(String rawText, VersionParser parser) {
        try {
            VersionValue parsed = parser.parse(rawText);
            return RegexCandidate.parsed(rawText, parsed);
        } catch (InvalidVersionException e) {
            return RegexCandidate.rejected(rawText, e.getMessage());
        }
    }
}
