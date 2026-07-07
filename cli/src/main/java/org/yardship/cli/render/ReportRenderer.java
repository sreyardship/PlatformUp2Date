package org.yardship.cli.render;

import org.yardship.cli.outcome.PointerResult;
import org.yardship.cli.outcome.RegexCandidate;
import org.yardship.cli.outcome.ValidationOutcome;

import java.io.PrintStream;
import java.util.List;

/**
 * Maps a {@link ValidationOutcome} to human-readable output on the given stream. Every
 * scheme-aware subcommand (regex now; pointer/changelog/calver-format/config later) shares this
 * renderer so the CLI's output shape stays consistent across subcommands.
 */
public final class ReportRenderer {

    /**
     * Writes a human-readable report of {@code outcome} to {@code out}.
     *
     * @return {@code outcome.exitCode()}, so callers can {@code return renderer.render(outcome, out)}
     *         directly from a picocli {@code call()}.
     */
    public int render(ValidationOutcome outcome, PrintStream out) {
        switch (outcome) {
            case ValidationOutcome.Ok ok -> renderOk(ok, out);
            case ValidationOutcome.ConfigInvalid invalid -> renderConfigInvalid(invalid, out);
            case ValidationOutcome.FetchFailed failed -> renderFetchFailed(failed, out);
            case ValidationOutcome.ValidButEmpty empty -> renderValidButEmpty(empty, out);
            case ValidationOutcome.PointerOk ok -> renderPointerOk(ok, out);
            case ValidationOutcome.PointerValidButEmpty empty -> renderPointerValidButEmpty(empty, out);
            case ValidationOutcome.ChangelogOk ok -> renderChangelogOk(ok, out);
        }
        return outcome.exitCode();
    }

    private void renderOk(ValidationOutcome.Ok ok, PrintStream out) {
        out.println("OK: " + ok.candidates().size() + " candidate(s) found.");
        renderCandidates(ok.candidates(), ok.winner(), out);
        out.println("Winner: " + ok.winner().rawText() + " -> " + ok.winner().parsed().get().value());
    }

    private void renderConfigInvalid(ValidationOutcome.ConfigInvalid invalid, PrintStream out) {
        out.println("CONFIG INVALID: " + invalid.message());
    }

    private void renderFetchFailed(ValidationOutcome.FetchFailed failed, PrintStream out) {
        out.println("FETCH FAILED: " + failed.message());
    }

    private void renderValidButEmpty(ValidationOutcome.ValidButEmpty empty, PrintStream out) {
        out.println("VALID BUT EMPTY: " + empty.candidates().size() + " candidate(s) found, none parseable.");
        renderCandidates(empty.candidates(), null, out);
    }

    private void renderPointerOk(ValidationOutcome.PointerOk ok, PrintStream out) {
        PointerResult result = ok.result();
        out.println("OK: pointer resolved to '" + result.rawText() + "'"
                + (result.strippedPreRelease() ? " (pre-release stripped)" : "") + ".");
        if (result.parsed().isPresent()) {
            out.println("Parsed: " + result.parsed().get().value());
        }
    }

    private void renderPointerValidButEmpty(ValidationOutcome.PointerValidButEmpty empty, PrintStream out) {
        out.println("VALID BUT EMPTY: " + empty.message());
        empty.attempted().ifPresent(result ->
                out.println("  - raw text: '" + result.rawText() + "'"
                        + (result.strippedPreRelease() ? " (pre-release stripped)" : "")));
    }

    private void renderChangelogOk(ValidationOutcome.ChangelogOk ok, PrintStream out) {
        out.println("OK: changelog URL resolved to '" + ok.resolvedUrl() + "'.");
    }

    private void renderCandidates(List<RegexCandidate> candidates, RegexCandidate winner, PrintStream out) {
        for (RegexCandidate candidate : candidates) {
            String marker = candidate.equals(winner) ? " [WINNER]" : "";
            if (candidate.isParsed()) {
                out.println("  - " + candidate.rawText() + " -> " + candidate.parsed().get().value() + marker);
            } else {
                out.println("  - " + candidate.rawText() + " -> rejected: " + candidate.rejectionReason().get());
            }
        }
    }
}
