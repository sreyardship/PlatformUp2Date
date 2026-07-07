package org.yardship.cli.outcome;

import java.util.List;

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
}
