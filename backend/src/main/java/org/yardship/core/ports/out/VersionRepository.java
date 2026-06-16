package org.yardship.core.ports.out;


public interface VersionRepository {

    /**
     * Scrape all configured applications. Per-app failures are isolated: a failing
     * upstream does not abort the scrape, it is counted in {@link ScrapeResult#failed()}.
     *
     * @return the resolved applications plus honest attempted/failed counts.
     */
    ScrapeResult scrape();
}
