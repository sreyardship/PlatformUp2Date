package org.yardship.core.ports.in;

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
}
