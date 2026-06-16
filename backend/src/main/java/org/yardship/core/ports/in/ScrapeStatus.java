package org.yardship.core.ports.in;

import io.quarkus.runtime.annotations.RegisterForReflection;

import org.yardship.core.ports.out.ScrapeResult;

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
        int retryAfterSeconds) {

    public ScrapeStatus {
        notNull(outcome);
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
                0);
    }

    /**
     * A successful manual scrape with no budget telemetry (budget fields 0). Retained for callers and
     * tests that do not exercise the budget.
     */
    public static ScrapeStatus scraped(int appsAttempted, int appsFailed) {
        return scraped(appsAttempted, appsFailed, 0, 0);
    }

    /**
     * The manual-scrape budget for the current window was exhausted — no scrape happened. Carries
     * {@code retryAfterSeconds} (when a slot frees); all counts and the other budget fields are 0.
     */
    public static ScrapeStatus rateLimited(int retryAfterSeconds) {
        return new ScrapeStatus(Outcome.RATE_LIMITED, 0, 0, 0, 0, 0, retryAfterSeconds);
    }

    /**
     * Another replica already holds the scrape lock — no scrape happened. All counts and budget
     * fields are 0.
     */
    public static ScrapeStatus inProgress() {
        return new ScrapeStatus(Outcome.IN_PROGRESS, 0, 0, 0, 0, 0, 0);
    }
}
