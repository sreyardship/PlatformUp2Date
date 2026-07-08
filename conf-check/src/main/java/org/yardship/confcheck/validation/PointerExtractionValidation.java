package org.yardship.confcheck.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.yardship.confcheck.outcome.PointerResult;
import org.yardship.confcheck.outcome.ValidationOutcome;
import org.yardship.core.domain.exceptions.InvalidVersionException;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionValue;

import java.io.IOException;
import java.util.Optional;

/**
 * Validates an {@code http} current source's {@code version-key} (a JSON Pointer, RFC 6901)
 * against a body: parses the body as JSON, resolves the pointer via Jackson's
 * {@code JsonNode.at(...)}, optionally strips the pre-release segment
 * ({@link org.yardship.core.domain.primitives.VersionValue#withoutPreRelease()}), and — when a
 * {@link VersionParser} is supplied — parses the (possibly stripped) extracted text and reports the
 * parsed version.
 *
 * <p>This transparently reimplements the extraction logic from the production
 * {@code HttpCurrentSource} (backend) — see
 * {@code backend/src/main/java/org/yardship/adapters/out/versionsource/current/http/HttpCurrentSource.java}
 * — rather than depending on it ({@code :cli} must not depend on {@code :backend}), so it can
 * report the outcome instead of only throwing.
 *
 * <ul>
 *   <li>Body is not valid JSON → {@link ValidationOutcome.PointerValidButEmpty} (no
 *       {@link org.yardship.confcheck.outcome.PointerResult} attempted — extraction never got a node to
 *       resolve against).</li>
 *   <li>Pointer resolves to nothing / an absent ({@code MissingNode}) / a non-textual node →
 *       {@link ValidationOutcome.PointerValidButEmpty} (no attempted result — there's no raw text to
 *       report).</li>
 *   <li>Pointer resolves to text, no {@code parser} supplied → {@link ValidationOutcome.PointerOk}
 *       with a {@link org.yardship.confcheck.outcome.PointerResult#extractedOnly extraction-only}
 *       result.</li>
 *   <li>Pointer resolves to text, {@code parser} supplied, text parses →
 *       {@link ValidationOutcome.PointerOk} with a parsed result.</li>
 *   <li>Pointer resolves to text, {@code parser} supplied, text fails to parse →
 *       {@link ValidationOutcome.PointerValidButEmpty} with an attempted (rejected) result — this is
 *       a reported outcome, not a thrown exception.</li>
 * </ul>
 *
 * <p>There is no well-defined "pre-release" concept for a bare, unparsed string —
 * {@link VersionValue#withoutPreRelease()} only exists on a parsed value — so {@code --scheme} is
 * required whenever {@code --strip-prerelease} is set, enforced one layer up in
 * {@code PointerCommand.call()} before this class is ever invoked (see
 * {@code PointerCommandWiringTests#stripPrerelease_withoutScheme_isConfigInvalid}). When a
 * {@code parser} is supplied, {@code --strip-prerelease} is applied to the successfully PARSED
 * {@link VersionValue} — mirroring production {@code HttpCurrentSource}'s
 * {@code parser.parse(text); return stripPrerelease ? version.withoutPreRelease() : version;} order
 * exactly — rather than to the raw extracted text before parsing. This class still defensively
 * guards the {@code stripPreRelease=true && parser.isEmpty()} combination (see {@link #validate})
 * rather than trusting the caller silently, but that combination should never be reachable from the
 * real CLI path.
 *
 * <p>{@link PointerResult#rawText()} always reports the ORIGINAL, unmodified extracted text (for
 * transparency/debugging — showing exactly what was extracted before any processing). The strip's
 * effect is visible instead in {@link PointerResult#parsed()}, which holds the (possibly-stripped)
 * {@link VersionValue}.
 */
public final class PointerExtractionValidation {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * @param body            the fetched/read body to parse as JSON.
     * @param pointer         an RFC 6901 JSON Pointer (e.g. {@code "/version"}).
     * @param stripPreRelease when {@code true}, apply {@code VersionValue.withoutPreRelease()} to
     *                        the successfully parsed value. Requires {@code parser} to be present
     *                        (there is no well-defined pre-release concept for unparsed text); the
     *                        real CLI path enforces this one layer up in
     *                        {@code PointerCommand.call()}.
     * @param parser          the scheme-configured parser when {@code --scheme} was given; empty
     *                        when it was not (extraction-only run).
     */
    public ValidationOutcome validate(
            String body, String pointer, boolean stripPreRelease, Optional<VersionParser> parser) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (IOException e) {
            return new ValidationOutcome.PointerValidButEmpty(
                    "Body is not valid JSON: " + e.getMessage(), Optional.empty());
        }
        if (root == null) {
            return new ValidationOutcome.PointerValidButEmpty(
                    "Body is not valid JSON: empty input", Optional.empty());
        }

        JsonNode node = root.at(pointer);
        if (node instanceof MissingNode || !node.isTextual()) {
            return new ValidationOutcome.PointerValidButEmpty(
                    "JSON Pointer '" + pointer + "' did not resolve to a text value", Optional.empty());
        }

        String rawText = node.textValue();

        if (parser.isEmpty()) {
            if (stripPreRelease) {
                throw new IllegalStateException(
                        "stripPreRelease=true requires a parser to be present (VersionValue.withoutPreRelease() "
                                + "only exists on a parsed value); PointerCommand.call() must reject this "
                                + "combination as ConfigInvalid before PointerExtractionValidation is invoked");
            }
            return new ValidationOutcome.PointerOk(PointerResult.extractedOnly(rawText, false));
        }

        try {
            VersionValue value = parser.get().parse(rawText);
            VersionValue reported = stripPreRelease ? value.withoutPreRelease() : value;
            return new ValidationOutcome.PointerOk(PointerResult.parsed(rawText, stripPreRelease, reported));
        } catch (InvalidVersionException e) {
            PointerResult attempted = PointerResult.rejected(rawText, stripPreRelease, e.getMessage());
            return new ValidationOutcome.PointerValidButEmpty(
                    "Extracted value '" + rawText + "' did not parse under the configured scheme: "
                            + e.getMessage(),
                    Optional.of(attempted));
        }
    }
}
