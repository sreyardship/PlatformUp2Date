package org.yardship.core.ports.in;

import org.yardship.core.domain.primitives.ScrapeTarget;
import org.yardship.core.domain.primitives.VersionApplication;

import java.util.List;

public interface ApplicationVersionPort {
    List<VersionApplication> getApplications();

    /**
     * Force a scrape now, bypassing the staleness check. Acquires the cluster-wide scrape lock:
     * if won, scrapes, writes a fresh snapshot (which resets the staleness clock) and returns a
     * {@code SCRAPED} status with per-app counts; if lost, returns {@code IN_PROGRESS} without
     * scraping or touching the clock.
     *
     * @throws RuntimeException if the backing store is unreachable (fail closed).
     */
    ScrapeStatus triggerScrape();

    /**
     * Refresh only the requested {@code (app, side)} targets rather than the whole fleet.
     *
     * <p>Lock-first: acquires the cluster-wide scrape lock; lost → {@code IN_PROGRESS} (no budget
     * spent, no write). Won → spends one slot from the existing scrape budget; exhausted → releases
     * the lock and returns {@code RATE_LIMITED}. Otherwise reads the current snapshot, splices each
     * target's requested side(s) over the matching app (leaving the other side untouched), and writes
     * the merged snapshot back re-supplying the snapshot's existing {@code lastAttemptAt} so the
     * fleet-wide staleness clock is not advanced. A single-side target for an app not yet in the
     * snapshot falls back to reading both sides. A target naming an app outside the configured
     * sources fails only that target ({@code reason == "not monitored"}); one source throwing
     * isolates to that target only. Returns {@code SCRAPED} with a {@link
     * org.yardship.core.domain.primitives.TargetResult} per target and budget telemetry — no version
     * data inline.
     *
     * @throws RuntimeException if the backing store is unreachable (fail closed).
     */
    ScrapeStatus targetedScrape(List<ScrapeTarget> targets);
}
