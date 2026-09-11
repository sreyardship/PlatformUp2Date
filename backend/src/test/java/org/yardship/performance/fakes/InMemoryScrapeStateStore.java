package org.yardship.performance.fakes;

import org.yardship.core.domain.primitives.ScrapeSnapshot;
import org.yardship.core.domain.primitives.VersionApplication;
import org.yardship.core.ports.out.ScrapeStateStore;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * In-memory fake for {@link ScrapeStateStore} used in the performance harness.
 *
 * <p>Starts empty (no snapshot), so the first {@link #read()} returns {@link Optional#empty()},
 * which forces {@code ApplicationVersionService.getApplications()} to perform a full scrape.
 * Subsequent reads return the last written snapshot.
 */
public class InMemoryScrapeStateStore implements ScrapeStateStore {

    private ScrapeSnapshot snapshot;

    @Override
    public Optional<ScrapeSnapshot> read() {
        return Optional.ofNullable(snapshot);
    }

    @Override
    public void write(List<VersionApplication> applications, Instant attemptAt) {
        this.snapshot = new ScrapeSnapshot(List.copyOf(applications), attemptAt);
    }

    /**
     * Clears the stored snapshot so the next {@link #read()} returns {@link Optional#empty()},
     * which forces {@code ApplicationVersionService.getApplications()} to re-run the full scrape
     * loop rather than serving the cached result.
     *
     * <p>Used by the performance harness between timed iterations to ensure each iteration
     * genuinely exercises the scrape path.
     */
    public void reset() {
        this.snapshot = null;
    }
}
