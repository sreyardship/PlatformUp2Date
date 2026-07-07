package org.yardship.cli.outcome;

import org.yardship.core.domain.primitives.VersionValue;

import java.util.Optional;

/**
 * The {@code pointer} subcommand's report line: the raw text a JSON Pointer resolved to (after
 * {@code --strip-prerelease} is applied, if requested), plus its optional parse result when
 * {@code --scheme} was given.
 *
 * <p>Unlike {@link RegexCandidate} (which always has a scheme and therefore always parses),
 * {@code --scheme} is optional for {@code pointer} — so {@link #parsed()} and
 * {@link #rejectionReason()} may BOTH be absent, meaning "no scheme was requested, this is
 * extraction-only". At most one of the two may be present; both present is invalid (see
 * {@link #schemeRequested()} to distinguish the "no scheme" case from the other two).
 */
public record PointerResult(
        String rawText,
        boolean strippedPreRelease,
        Optional<VersionValue> parsed,
        Optional<String> rejectionReason) {

    public PointerResult {
        if (parsed.isPresent() && rejectionReason.isPresent()) {
            throw new IllegalArgumentException(
                    "PointerResult must not have both parsed and rejectionReason present");
        }
    }

    /** No {@code --scheme} was given: the pointer resolved to text, nothing more was attempted. */
    public static PointerResult extractedOnly(String rawText, boolean strippedPreRelease) {
        return new PointerResult(rawText, strippedPreRelease, Optional.empty(), Optional.empty());
    }

    /** {@code --scheme} was given and the extracted text parsed successfully. */
    public static PointerResult parsed(String rawText, boolean strippedPreRelease, VersionValue value) {
        return new PointerResult(rawText, strippedPreRelease, Optional.of(value), Optional.empty());
    }

    /** {@code --scheme} was given but the extracted text failed to parse under it. */
    public static PointerResult rejected(String rawText, boolean strippedPreRelease, String reason) {
        return new PointerResult(rawText, strippedPreRelease, Optional.empty(), Optional.of(reason));
    }

    /** {@code true} if {@code --scheme} was given (i.e. a parse was attempted, win or lose). */
    public boolean schemeRequested() {
        return parsed.isPresent() || rejectionReason.isPresent();
    }
}
