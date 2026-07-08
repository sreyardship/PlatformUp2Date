package org.yardship.confcheck.outcome;

import org.yardship.core.domain.primitives.VersionValue;

import java.util.Optional;

/**
 * One regex match's report line: the raw capture-group-1 text, plus its parse result. Exactly one
 * of {@link #parsed()} / {@link #rejectionReason()} is present — a candidate either parsed
 * successfully under the configured {@link org.yardship.core.domain.primitives.VersionParser} or it
 * didn't, and if it didn't the reason (the {@link org.yardship.core.domain.exceptions.InvalidVersionException}
 * message) is retained so the report can explain why it lost.
 */
public record RegexCandidate(String rawText, Optional<VersionValue> parsed, Optional<String> rejectionReason) {

    public RegexCandidate {
        if (parsed.isPresent() == rejectionReason.isPresent()) {
            throw new IllegalArgumentException(
                    "RegexCandidate must have exactly one of parsed/rejectionReason present");
        }
    }

    public static RegexCandidate parsed(String rawText, VersionValue value) {
        return new RegexCandidate(rawText, Optional.of(value), Optional.empty());
    }

    public static RegexCandidate rejected(String rawText, String reason) {
        return new RegexCandidate(rawText, Optional.empty(), Optional.of(reason));
    }

    public boolean isParsed() {
        return parsed.isPresent();
    }
}
