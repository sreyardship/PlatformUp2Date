package org.yardship.confcheck.outcome;

import org.yardship.core.domain.primitives.VersionValue;

import java.util.Optional;

/**
 * The {@code header} subcommand's report line: the status code the header was read from (ALWAYS
 * present — a {@link org.yardship.confcheck.port.ResponseSource} always yields a response, so the
 * status is knowable regardless of whether the header itself resolved to anything usable), the raw
 * (trimmed) header text when the header was found, and its optional parse result when a scheme was
 * given.
 *
 * <p>Carrying {@code statusCode} unconditionally is the point of this type: per
 * {@code docs/adr/0030-http-header-current-source.md}, an {@code http-header} source reads its
 * header off the final response WHATEVER the status code was (a secured Jenkins answering 403 is
 * the motivating case), so an operator reading a report must be able to see they are reading a
 * version off a non-2xx response and that this is by design, not a fluke.
 *
 * <p>Mirrors {@link PointerResult}'s optional-scheme shape (at most one of {@link #parsed()} /
 * {@link #rejectionReason()} present; both absent means "no scheme was requested, extraction-only"),
 * plus the {@code statusCode} field {@code pointer} has no equivalent for. Also mirrors
 * {@link PointerResult#strippedPreRelease()}: whether {@code --strip-prerelease} was applied to the
 * successfully parsed {@link #parsed()} value, so the renderer can note it the same way
 * {@code renderPointerOk} does.
 */
public record HeaderResult(
        int statusCode,
        Optional<String> rawText,
        boolean strippedPreRelease,
        Optional<VersionValue> parsed,
        Optional<String> rejectionReason) {

    public HeaderResult {
        if (parsed.isPresent() && rejectionReason.isPresent()) {
            throw new IllegalArgumentException(
                    "HeaderResult must not have both parsed and rejectionReason present");
        }
        if (rawText.isEmpty() && (parsed.isPresent() || rejectionReason.isPresent())) {
            throw new IllegalArgumentException(
                    "HeaderResult must not carry parsed/rejectionReason without rawText");
        }
    }

    /** The header was absent from the response entirely: no raw text was ever obtained. */
    public static HeaderResult absent(int statusCode) {
        return new HeaderResult(statusCode, Optional.empty(), false, Optional.empty(), Optional.empty());
    }

    /** The header was present but resolved to an empty string after trimming. */
    public static HeaderResult presentButEmpty(int statusCode) {
        return new HeaderResult(statusCode, Optional.of(""), false, Optional.empty(), Optional.empty());
    }

    /** No scheme was given (extraction-only): the header resolved to {@code rawText}, nothing more attempted. */
    public static HeaderResult extractedOnly(int statusCode, String rawText) {
        return new HeaderResult(statusCode, Optional.of(rawText), false, Optional.empty(), Optional.empty());
    }

    /** A scheme was given and {@code rawText} parsed successfully into {@code value}. */
    public static HeaderResult parsed(int statusCode, String rawText, boolean strippedPreRelease, VersionValue value) {
        return new HeaderResult(statusCode, Optional.of(rawText), strippedPreRelease, Optional.of(value), Optional.empty());
    }

    /** A scheme was given but {@code rawText} failed to parse/match under it. */
    public static HeaderResult rejected(int statusCode, String rawText, boolean strippedPreRelease, String reason) {
        return new HeaderResult(statusCode, Optional.of(rawText), strippedPreRelease, Optional.empty(), Optional.of(reason));
    }

    /** {@code true} if a scheme (and/or regex requiring a scheme to select among matches) was requested. */
    public boolean schemeRequested() {
        return parsed.isPresent() || rejectionReason.isPresent();
    }
}
