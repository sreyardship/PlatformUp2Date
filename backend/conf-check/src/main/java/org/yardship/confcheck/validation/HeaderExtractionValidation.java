package org.yardship.confcheck.validation;

import org.yardship.confcheck.outcome.HeaderResult;
import org.yardship.confcheck.outcome.ValidationOutcome;
import org.yardship.confcheck.port.ResponseSource;
import org.yardship.core.domain.exceptions.InvalidVersionException;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionPattern;
import org.yardship.core.domain.primitives.VersionValue;

import java.util.List;
import java.util.Optional;

/**
 * Validates an {@code http-header} current source's {@code version-header} (+ optional
 * {@code regex}) against a {@link ResponseSource.Response}: looks the header up
 * case-insensitively, takes the first value of a repeated header, trims it, and — when a
 * {@link VersionParser} is supplied — parses the (possibly regex-extracted, possibly stripped)
 * text and reports the parsed version.
 *
 * <p>This transparently reimplements the extraction logic from the production
 * {@code HttpHeaderCurrentSource} (backend, slice 03) — see
 * {@code backend/server/src/main/java/org/yardship/adapters/out/versionsource/current/httpheader/HttpHeaderCurrentSource.java}
 * — rather than depending on it ({@code :backend:conf-check} must not depend on
 * {@code :backend:server}), so it can report the outcome instead of only throwing. Per
 * {@code docs/adr/0030-http-header-current-source.md}, the status code is consulted ONLY for
 * composing messages/results, NEVER to gate whether the header is read — a non-2xx
 * {@link ResponseSource.Response} is validated exactly like a 2xx one.
 *
 * <p>The optional {@code regex}'s selection rule is "first match that parses", never "largest" —
 * see ADR-0030, "The first match, not the largest": a current version is a single observation, not
 * a selection. Regex compilation/capture-group validation is shared with
 * {@link RegexExtractionValidation} via {@code :backend:domain}'s
 * {@link org.yardship.core.domain.primitives.VersionPattern} rather than reimplemented a third
 * time.
 *
 * <ul>
 *   <li>Header absent from the response → {@link ValidationOutcome.HeaderValidButEmpty}, naming
 *       the observed status code, no raw text attached.</li>
 *   <li>Header present but empty after trimming → {@link ValidationOutcome.HeaderValidButEmpty},
 *       naming the observed status code, distinct message from "absent".</li>
 *   <li>No {@code regex}, no {@code parser} → {@link ValidationOutcome.HeaderOk} with an
 *       extraction-only result (the trimmed header value).</li>
 *   <li>No {@code regex}, {@code parser} supplied, trimmed value parses →
 *       {@link ValidationOutcome.HeaderOk} with a parsed result.</li>
 *   <li>No {@code regex}, {@code parser} supplied, trimmed value fails to parse →
 *       {@link ValidationOutcome.HeaderValidButEmpty}, carrying the attempted value.</li>
 *   <li>{@code regex} supplied, no {@code parser} → {@link ValidationOutcome.HeaderOk} with the
 *       FIRST match's capture group 1 as extraction-only text.</li>
 *   <li>{@code regex} supplied, {@code parser} supplied → the first match whose capture group 1
 *       parses wins (later, larger matches are never preferred); if none parse,
 *       {@link ValidationOutcome.HeaderValidButEmpty} carrying the whole trimmed header value.</li>
 * </ul>
 *
 * <p>{@code stripPreRelease} is applied to the successfully PARSED {@link VersionValue}, never to
 * the raw extracted text, so {@link HeaderResult#rawText()} always reports what was actually
 * extracted, unmodified — matching {@link PointerExtractionValidation}. It differs in one respect:
 * where {@code PointerExtractionValidation} returns {@link ValidationOutcome.ConfigInvalid} for
 * {@code stripPreRelease} without a parser, this validator silently ignores the flag, because that
 * guard lives in {@code HeaderCommand} instead — the only entry point that can supply the
 * combination.
 */
public final class HeaderExtractionValidation {

    /**
     * @param response        the fetched/read response to validate.
     * @param headerName      the header name to look up (case-insensitive).
     * @param regex           a Java regex with at least one capture group; group 1 is the
     *                        candidate text. Absent = the whole trimmed header value is used
     *                        directly.
     * @param stripPreRelease when {@code true}, apply {@code VersionValue.withoutPreRelease()} to
     *                        the successfully parsed value. Has no effect when {@code parser} is
     *                        absent.
     * @param parser          the scheme-configured parser when {@code --scheme} was given; empty
     *                        when it was not (extraction-only run).
     */
    public ValidationOutcome validate(
            ResponseSource.Response response,
            String headerName,
            Optional<String> regex,
            boolean stripPreRelease,
            Optional<VersionParser> parser) {

        VersionPattern pattern = null;
        if (regex.isPresent()) {
            try {
                pattern = new VersionPattern(regex.get());
            } catch (IllegalArgumentException e) {
                return new ValidationOutcome.ConfigInvalid(e.getMessage());
            }
        }

        int statusCode = response.statusCode();
        Optional<String> rawHeaderValue = response.firstHeader(headerName);
        if (rawHeaderValue.isEmpty()) {
            return absent(statusCode, headerName);
        }

        String trimmed = rawHeaderValue.get().trim();
        if (trimmed.isEmpty()) {
            return presentButEmpty(statusCode, headerName);
        }

        return (pattern != null)
                ? validateWithRegex(statusCode, trimmed, pattern, stripPreRelease, parser)
                : validateWholeValue(statusCode, trimmed, stripPreRelease, parser);
    }

    private ValidationOutcome validateWholeValue(
            int statusCode, String trimmed, boolean stripPreRelease, Optional<VersionParser> parser) {
        if (parser.isEmpty()) {
            return new ValidationOutcome.HeaderOk(HeaderResult.extractedOnly(statusCode, trimmed));
        }
        try {
            VersionValue value = parser.get().parse(trimmed);
            VersionValue reported = stripPreRelease ? value.withoutPreRelease() : value;
            return new ValidationOutcome.HeaderOk(HeaderResult.parsed(statusCode, trimmed, stripPreRelease, reported));
        } catch (InvalidVersionException e) {
            return new ValidationOutcome.HeaderValidButEmpty(
                    "Header value '" + trimmed + "' did not parse under the configured scheme (status "
                            + statusCode + "): " + e.getMessage(),
                    HeaderResult.rejected(statusCode, trimmed, stripPreRelease, e.getMessage()));
        }
    }

    private ValidationOutcome validateWithRegex(
            int statusCode, String trimmed, VersionPattern pattern, boolean stripPreRelease, Optional<VersionParser> parser) {
        List<String> candidates = pattern.rawCandidates(trimmed);

        if (parser.isEmpty()) {
            if (!candidates.isEmpty()) {
                return new ValidationOutcome.HeaderOk(HeaderResult.extractedOnly(statusCode, candidates.get(0)));
            }
            return new ValidationOutcome.HeaderValidButEmpty(
                    "No match for the configured regex in header value '" + trimmed + "' (status " + statusCode + ").",
                    HeaderResult.rejected(statusCode, trimmed, stripPreRelease, "no match"));
        }

        for (String candidateText : candidates) {
            try {
                VersionValue value = parser.get().parse(candidateText);
                VersionValue reported = stripPreRelease ? value.withoutPreRelease() : value;
                return new ValidationOutcome.HeaderOk(HeaderResult.parsed(statusCode, candidateText, stripPreRelease, reported));
            } catch (InvalidVersionException ignored) {
                // Try the next match; the first PARSEABLE match wins, per ADR-0030.
            }
        }
        return new ValidationOutcome.HeaderValidButEmpty(
                "No match for the configured regex in header value '" + trimmed
                        + "' parsed under the configured scheme (status " + statusCode + ").",
                HeaderResult.rejected(statusCode, trimmed, stripPreRelease, "no parseable match"));
    }

    private ValidationOutcome absent(int statusCode, String headerName) {
        return new ValidationOutcome.HeaderValidButEmpty(
                "Header '" + headerName + "' was absent from the response (status " + statusCode + ").",
                HeaderResult.absent(statusCode));
    }

    private ValidationOutcome presentButEmpty(int statusCode, String headerName) {
        return new ValidationOutcome.HeaderValidButEmpty(
                "Header '" + headerName + "' was present but empty after trimming (status "
                        + statusCode + ").",
                HeaderResult.presentButEmpty(statusCode));
    }
}
