package org.yardship.core.ports.in;

import io.quarkus.runtime.annotations.RegisterForReflection;

import org.yardship.core.domain.primitives.TargetResult;
import org.yardship.core.ports.out.ScrapeResult;

import java.util.List;

import static org.yardship.core.domain.primitives.DomainValidator.notNull;

/**
 * The result of REQUESTING a manual scrape, returned by the inbound use case and serialised
 * verbatim over HTTP (and later MCP).
 *
 * <p>{@code outcome} says what happened; the {@code appsAttempted/appsSucceeded/appsFailed}
 * counts are populated from the {@link ScrapeResult} on a {@code SCRAPED} outcome (and are 0
 * otherwise). The budget fields — {@code triggersRemaining}, {@code windowResetsInSeconds}
 * (relevant on SCRAPED) and {@code retryAfterSeconds} (relevant on RATE_LIMITED) — are carried
 * here so slice 04 can populate them without reshaping the record; this slice always sets them
 * to 0.
 *
 * <p>{@code targetResults} carries the per-target outcomes of a targeted scrape (one
 * {@link TargetResult} per requested {@code ScrapeTarget}); the full-fleet path passes an empty
 * list since it has no individual targets to report.
 *
 * <p>Invariant: {@code appsSucceeded == appsAttempted - appsFailed}.
 */
@RegisterForReflection
public record ScrapeStatus(
        Outcome outcome,
        int appsAttempted,
        int appsSucceeded,
        int appsFailed,
        int triggersRemaining,
        int windowResetsInSeconds,
        int retryAfterSeconds,
        List<TargetResult> targetResults) {

    public ScrapeStatus {
        notNull(outcome);
        notNull(targetResults);
    }

    /**
     * A successful manual scrape with budget telemetry. Counts are mapped from the scrape result;
     * {@code triggersRemaining}/{@code windowResetsInSeconds} come from the budget decision spent for
     * this trigger. {@code retryAfterSeconds} is 0 (not rate-limited).
     */
    public static ScrapeStatus scraped(
            int appsAttempted, int appsFailed, int triggersRemaining, int windowResetsInSeconds) {
        return new ScrapeStatus(
                Outcome.SCRAPED,
                appsAttempted,
                appsAttempted - appsFailed,
                appsFailed,
                triggersRemaining,
                windowResetsInSeconds,
                0,
                List.of());
    }

    /**
     * A successful manual scrape with no budget telemetry (budget fields 0). Retained for callers and
     * tests that do not exercise the budget.
     */
    public static ScrapeStatus scraped(int appsAttempted, int appsFailed) {
        return scraped(appsAttempted, appsFailed, 0, 0);
    }

    /**
     * A successful FULL scrape carrying both the app counts AND the per-app {@link TargetResult}s
     * (one per configured app, {@code side == BOTH} — see docs/adr/0006). Unlike the targeted-scrape
     * overload, the app counts here ARE meaningful and are taken verbatim from the caller (not
     * re-derived from {@code targetResults}), so they stay byte-for-byte consistent with the
     * {@link org.yardship.core.ports.out.ScrapeResult} the service computed.
     */
    public static ScrapeStatus scraped(
            int appsAttempted,
            int appsFailed,
            int triggersRemaining,
            int windowResetsInSeconds,
            List<TargetResult> targetResults) {
        return new ScrapeStatus(
                Outcome.SCRAPED,
                appsAttempted,
                appsAttempted - appsFailed,
                appsFailed,
                triggersRemaining,
                windowResetsInSeconds,
                0,
                targetResults);
    }

    /**
     * A successful targeted scrape: carries the per-target results and the budget telemetry spent
     * for this call. The app counts are not meaningful for a targeted scrape and are left at 0.
     */
    public static ScrapeStatus scraped(
            List<TargetResult> targetResults, int triggersRemaining, int windowResetsInSeconds) {
        return new ScrapeStatus(
                Outcome.SCRAPED, 0, 0, 0, triggersRemaining, windowResetsInSeconds, 0, targetResults);
    }

    /**
     * The manual-scrape budget for the current window was exhausted — no scrape happened. Carries
     * {@code retryAfterSeconds} (when a slot frees); all counts and the other budget fields are 0.
     */
    public static ScrapeStatus rateLimited(int retryAfterSeconds) {
        return new ScrapeStatus(Outcome.RATE_LIMITED, 0, 0, 0, 0, 0, retryAfterSeconds, List.of());
    }

    /**
     * Another replica already holds the scrape lock — no scrape happened. All counts and budget
     * fields are 0.
     */
    public static ScrapeStatus inProgress() {
        return new ScrapeStatus(Outcome.IN_PROGRESS, 0, 0, 0, 0, 0, 0, List.of());
    }
}
