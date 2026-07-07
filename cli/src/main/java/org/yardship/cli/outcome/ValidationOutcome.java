package org.yardship.cli.outcome;

import java.util.List;
import java.util.Optional;

/**
 * The result of a CLI validation, sealed to the four outcome kinds every scheme-aware validator
 * (regex, pointer, changelog, calver-format, config — issues 02-06) can produce. Each case carries
 * a distinct process exit code so scripts/CI invoking the CLI can branch on failure kind without
 * parsing output text.
 *
 * <p>Exit code contract (stable — do not renumber once shipped):
 * <ul>
 *   <li>{@link Ok#EXIT_CODE} = 0 — success, mirrors the conventional "no error" shell exit code.</li>
 *   <li>{@link ConfigInvalid#EXIT_CODE} = 2 — the config itself is wrong (bad regex, no capture
 *       group 1, bad calver-format, ...). Mirrors what the backend rejects at boot.</li>
 *   <li>{@link FetchFailed#EXIT_CODE} = 3 — body acquisition failed (live fetch or offline read).
 *       Mirrors {@code VersionFetchException} for the live case. Produced by both fetch-backed
 *       ({@code --url}) and offline ({@code --body-file} / stdin) body sources.</li>
 *   <li>{@link ValidButEmpty#EXIT_CODE} = 4 — config compiled and a body was obtained, but nothing
 *       usable came out of it (zero matches / only unparseable matches). Mirrors the backend's
 *       "no parseable version matched" scrape-failure state.</li>
 * </ul>
 * (1 is deliberately skipped: reserved for uncaught/unexpected errors — e.g. a bug or an I/O failure
 * outside the fetch path — so it stays distinguishable from the four deliberate outcomes above.)
 *
 * <p><b>Design note (issue 03):</b> {@link Ok} and {@link ValidButEmpty} above are shaped around
 * {@code regex}'s "list of candidates, one winner" result. {@code pointer} always extracts at most
 * one value, and the failure kinds are different (body not JSON / pointer absent / non-textual —
 * not "candidates that failed to parse"), so its success/valid-but-empty payload doesn't fit those
 * two records. Rather than force-fit it (e.g. wrapping a single {@link PointerResult} in a
 * one-element {@code List<RegexCandidate>}, which would lose the "no --scheme given" and
 * "not JSON" cases), {@link PointerOk} / {@link PointerValidButEmpty} are added as siblings within
 * this same sealed interface — {@link ConfigInvalid} and {@link FetchFailed} are reused completely
 * unchanged (both are already payload-generic: just a message). Exit codes are shared with the
 * regex-shaped cases at the same value, since the exit-code contract is about failure *kind*, not
 * which subcommand produced it.
 */
public sealed interface ValidationOutcome {

    /** The process exit code this outcome maps to. */
    int exitCode();

    /**
     * Validation succeeded: at least one candidate parsed, {@code winner} is the largest.
     *
     * @param candidates every candidate considered, in the order found.
     * @param winner     the largest parseable candidate; always present among {@code candidates}.
     */
    record Ok(List<RegexCandidate> candidates, RegexCandidate winner) implements ValidationOutcome {
        public static final int EXIT_CODE = 0;

        @Override
        public int exitCode() {
            return EXIT_CODE;
        }
    }

    /**
     * The config itself is invalid — e.g. the regex fails to compile, or it has no capture group 1,
     * or the scheme/calver-format combination is invalid. Never reaches the fetch step.
     */
    record ConfigInvalid(String message) implements ValidationOutcome {
        public static final int EXIT_CODE = 2;

        @Override
        public int exitCode() {
            return EXIT_CODE;
        }
    }

    /**
     * Body acquisition failed: a live fetch (via {@code --url}) hit a network error, timeout, or
     * non-2xx response, or an offline body source ({@code --body-file} / stdin) couldn't be read
     * (e.g. missing file, stdin I/O error).
     */
    record FetchFailed(String message) implements ValidationOutcome {
        public static final int EXIT_CODE = 3;

        @Override
        public int exitCode() {
            return EXIT_CODE;
        }
    }

    /**
     * Config compiled and a body was obtained, but nothing usable resulted — zero regex matches,
     * or every match's capture group 1 failed to parse under the configured scheme.
     *
     * @param candidates every candidate considered (possibly empty), in the order found.
     */
    record ValidButEmpty(List<RegexCandidate> candidates) implements ValidationOutcome {
        public static final int EXIT_CODE = 4;

        @Override
        public int exitCode() {
            return EXIT_CODE;
        }
    }

    /**
     * {@code pointer} validation succeeded: the JSON Pointer resolved to a text value (and, if
     * {@code --scheme} was given, that value parsed).
     */
    record PointerOk(PointerResult result) implements ValidationOutcome {
        public static final int EXIT_CODE = 0;

        @Override
        public int exitCode() {
            return EXIT_CODE;
        }
    }

    /**
     * {@code pointer} validation found nothing usable: the body wasn't JSON, the pointer resolved
     * to nothing/an absent node/a non-textual node, or (with {@code --scheme} given) the extracted
     * text failed to parse.
     *
     * @param message   a human-readable explanation of why nothing usable resulted.
     * @param attempted the partial result when extraction got far enough to have raw text (e.g. a
     *                  parse failure under {@code --scheme}); empty when extraction itself failed
     *                  (body not JSON, pointer absent/non-textual).
     */
    record PointerValidButEmpty(String message, Optional<PointerResult> attempted) implements ValidationOutcome {
        public static final int EXIT_CODE = 4;

        @Override
        public int exitCode() {
            return EXIT_CODE;
        }
    }
}
