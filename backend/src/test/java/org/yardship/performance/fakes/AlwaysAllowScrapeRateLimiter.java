package org.yardship.performance.fakes;

import org.yardship.core.ports.out.ScrapeRateLimiter;

import java.time.Instant;

/**
 * In-memory fake for {@link ScrapeRateLimiter} used in the performance harness.
 *
 * <p>{@link #tryAcquire(Instant)} always returns an allowed {@link Decision} with an unlimited
 * remaining budget. {@link #peek(Instant)} also reports unlimited remaining budget.
 *
 * <p>Note: the {@code getApplications()} code path does NOT call the rate limiter at all —
 * it only uses the lock. Both the full-scrape and targeted-scrape rate limiters are required
 * by the {@link org.yardship.core.services.ApplicationVersionService} constructor signature,
 * so this fake satisfies both without restricting throughput.
 */
public class AlwaysAllowScrapeRateLimiter implements ScrapeRateLimiter {

    @Override
    public Decision tryAcquire(Instant now) {
        return Decision.allowed(Integer.MAX_VALUE, 0L);
    }

    @Override
    public Budget peek(Instant now) {
        return new Budget(Integer.MAX_VALUE, 0L);
    }
}
