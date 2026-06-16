package org.yardship.unit.core.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yardship.core.ports.out.ScrapeResult;
import org.yardship.core.domain.primitives.ScrapeSnapshot;
import org.yardship.core.domain.primitives.Version;
import org.yardship.core.domain.primitives.VersionApplication;
import org.yardship.core.ports.out.ScrapeLock;
import org.yardship.core.ports.out.ScrapeRateLimiter;
import org.yardship.core.ports.out.ScrapeStateStore;
import org.yardship.core.ports.out.VersionRepository;
import org.yardship.core.services.ApplicationVersionService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ApplicationVersionService#getApplications()} against the new
 * Valkey-backed scrape-state model. The service holds NO in-memory cache: it reads a
 * {@link ScrapeSnapshot} from the {@link ScrapeStateStore} port, and when that snapshot is
 * stale (or absent) it scrapes the {@link VersionRepository} and writes a fresh snapshot.
 *
 * <p>Staleness is evaluated against the snapshot's {@code lastAttemptAt}, the injected
 * {@link Clock}, and the configured scrape-interval:
 * {@code now - lastAttemptAt >= scrapeInterval} (or no snapshot at all).
 *
 * <p>The store is a hand-written {@link FakeScrapeStateStore} implementing the port; the
 * repository is a Mockito mock; the clock is the {@link MutableClock} seam.
 */
class VersionServiceTests {

    private static final Duration SCRAPE_INTERVAL = Duration.ofHours(1);
    private static final Instant START = Instant.parse("2026-06-08T00:00:00Z");

    private VersionRepository versionRepository;
    private FakeScrapeStateStore store;
    private FakeScrapeLock lock;
    private FakeScrapeRateLimiter rateLimiter;
    private MutableClock clock;
    private ApplicationVersionService sut;

    @BeforeEach
    void setUp() {
        versionRepository = mock(VersionRepository.class);
        store = new FakeScrapeStateStore();
        lock = new FakeScrapeLock();
        rateLimiter = new FakeScrapeRateLimiter();
        clock = new MutableClock(START);
        sut = new ApplicationVersionService(versionRepository, store, lock, rateLimiter, SCRAPE_INTERVAL, clock);
    }

    @Test
    void getApplications_servesFreshSnapshot_withoutScrapingOrWriting() {
        VersionApplication app = createOldApplication();
        // A snapshot attempted "just now" is fresh — no scrape, no write expected.
        store.seed(new ScrapeSnapshot(List.of(app), START));

        List<VersionApplication> result = sut.getApplications();

        assertEquals(List.of(app), result);
        verify(versionRepository, never()).scrape();
        assertEquals(0, store.writeCount, "fresh snapshot must not be re-written");
    }

    @Test
    void getApplications_servesFreshSnapshot_whenJustShyOfInterval() {
        VersionApplication app = createOldApplication();
        store.seed(new ScrapeSnapshot(List.of(app), START));
        clock.advance(SCRAPE_INTERVAL.minusSeconds(1));

        sut.getApplications();

        verify(versionRepository, never()).scrape();
        assertEquals(0, store.writeCount);
    }

    @Test
    void getApplications_scrapesAndWrites_whenNoSnapshotExists() {
        VersionApplication scraped = createOldApplication();
        when(versionRepository.scrape())
                .thenReturn(new ScrapeResult(List.of(scraped), 1, 0));

        List<VersionApplication> result = sut.getApplications();

        assertEquals(List.of(scraped), result);
        verify(versionRepository, times(1)).scrape();
        assertEquals(1, store.writeCount, "an absent snapshot must trigger a write");
        assertEquals(List.of(scraped), store.lastWrittenApps);
        assertEquals(START, store.lastWrittenAttemptAt, "write must record the attempt instant from the clock");
    }

    @Test
    void getApplications_scrapesAndWrites_whenSnapshotIsStale() {
        VersionApplication stale = createUp2DateApplication();
        store.seed(new ScrapeSnapshot(List.of(stale), START));

        VersionApplication fresh = createOldApplication();
        when(versionRepository.scrape())
                .thenReturn(new ScrapeResult(List.of(fresh), 1, 0));

        clock.advance(SCRAPE_INTERVAL);
        List<VersionApplication> result = sut.getApplications();

        assertEquals(List.of(fresh), result);
        verify(versionRepository, times(1)).scrape();
        assertEquals(1, store.writeCount);
        assertEquals(START.plus(SCRAPE_INTERVAL), store.lastWrittenAttemptAt);
    }

    @Test
    void getApplications_returnsScrapedApps_evenWhenEmpty() {
        when(versionRepository.scrape())
                .thenReturn(new ScrapeResult(List.of(), 0, 0));

        List<VersionApplication> result = sut.getApplications();

        assertTrue(result.isEmpty());
        assertEquals(1, store.writeCount);
    }

    @Test
    void getApplications_propagatesStoreReadFailure_failClosed() {
        // Valkey unreachable on read: the service must NOT swallow it into an empty list.
        store.failReadWith(new RuntimeException("valkey unreachable"));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> sut.getApplications());
        assertEquals("valkey unreachable", ex.getMessage());
        verify(versionRepository, never()).scrape();
    }

    @Test
    void getApplications_propagatesStoreWriteFailure_failClosed() {
        // No snapshot → scrape succeeds → write to Valkey fails → must propagate, not swallow.
        when(versionRepository.scrape())
                .thenReturn(new ScrapeResult(List.of(createOldApplication()), 1, 0));
        store.failWriteWith(new RuntimeException("valkey write failed"));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> sut.getApplications());
        assertEquals("valkey write failed", ex.getMessage());
    }

    @Test
    void getApplications_propagatesRepositoryFailure_failClosed() {
        // Stale snapshot → scrape attempted → repository blows up → must propagate (no fallback).
        store.seed(new ScrapeSnapshot(List.of(createUp2DateApplication()), START));
        when(versionRepository.scrape()).thenThrow(new RuntimeException("scrape failed"));
        clock.advance(SCRAPE_INTERVAL);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> sut.getApplications());
        assertEquals("scrape failed", ex.getMessage());
    }

    // --- Distributed scrape lock (Issue 02) ---------------------------------------------------
    //
    // The lock guards the SCRAPE branch only. On a stale (or absent) snapshot a caller tries to
    // acquire the cluster-wide lock: the winner scrapes + writes + releases; the losers serve the
    // current shared snapshot WITHOUT hitting upstreams. A fresh snapshot never touches the lock.

    @Test
    void getApplications_freshSnapshot_neverTouchesTheLock() {
        store.seed(new ScrapeSnapshot(List.of(createOldApplication()), START));

        sut.getApplications();

        assertEquals(0, lock.acquireCount, "a fresh snapshot must be served without acquiring the lock");
        assertEquals(0, lock.releaseCount);
        verify(versionRepository, never()).scrape();
    }

    @Test
    void getApplications_staleSnapshot_lockWon_scrapesWritesAndReleases() {
        store.seed(new ScrapeSnapshot(List.of(createUp2DateApplication()), START));
        VersionApplication fresh = createOldApplication();
        when(versionRepository.scrape()).thenReturn(new ScrapeResult(List.of(fresh), 1, 0));
        lock.willAcquire(true);
        clock.advance(SCRAPE_INTERVAL);

        List<VersionApplication> result = sut.getApplications();

        assertEquals(List.of(fresh), result, "the lock winner returns the freshly scraped apps");
        assertEquals(1, lock.acquireCount, "the winner must try to acquire exactly once");
        verify(versionRepository, times(1)).scrape();
        assertEquals(1, store.writeCount, "the winner must write the new snapshot");
        assertEquals(1, lock.releaseCount, "the winner must release the lock");
    }

    @Test
    void getApplications_staleSnapshot_lockLost_servesSnapshotWithoutScraping() {
        VersionApplication shared = createUp2DateApplication();
        store.seed(new ScrapeSnapshot(List.of(shared), START));
        lock.willAcquire(false);
        clock.advance(SCRAPE_INTERVAL);

        List<VersionApplication> result = sut.getApplications();

        assertEquals(List.of(shared), result, "a loser serves the current shared snapshot");
        verify(versionRepository, never()).scrape();
        assertEquals(0, store.writeCount, "a loser must not write");
        assertEquals(0, lock.releaseCount, "a loser holds nothing, so it must not release");
    }

    @Test
    void getApplications_lockLost_withNoSnapshotYet_servesEmptyWithoutScraping() {
        // Edge case: stale (here: absent) snapshot, lock lost, and nothing has ever been written.
        // The loser must NOT scrape; it serves the last-known result, which is empty.
        lock.willAcquire(false);

        List<VersionApplication> result = sut.getApplications();

        assertTrue(result.isEmpty(), "no snapshot + lost lock serves an empty last-known result");
        verify(versionRepository, never()).scrape();
        assertEquals(0, store.writeCount);
    }

    @Test
    void getApplications_lockWinner_releasesEvenWhenScrapeThrows() {
        store.seed(new ScrapeSnapshot(List.of(createUp2DateApplication()), START));
        lock.willAcquire(true);
        when(versionRepository.scrape()).thenThrow(new RuntimeException("scrape failed"));
        clock.advance(SCRAPE_INTERVAL);

        assertThrows(RuntimeException.class, () -> sut.getApplications());

        assertEquals(1, lock.releaseCount, "the lock must be released even when the scrape blows up");
    }

    @Test
    void getApplications_lockWinner_releasesEvenWhenWriteThrows() {
        lock.willAcquire(true);
        when(versionRepository.scrape())
                .thenReturn(new ScrapeResult(List.of(createOldApplication()), 1, 0));
        store.failWriteWith(new RuntimeException("valkey write failed"));

        assertThrows(RuntimeException.class, () -> sut.getApplications());

        assertEquals(1, lock.releaseCount, "the lock must be released even when the snapshot write fails");
    }

    // --- Scrape budget is manual-only (Issue 04) ----------------------------------------------
    //
    // The rolling-window budget caps MANUAL triggers (triggerScrape) only. The automatic staleness
    // scrape on getApplications must NEVER consult the rate limiter — a stale snapshot always refreshes
    // regardless of how many manual triggers were spent.

    @Test
    void getApplications_staleSnapshot_neverConsultsTheRateLimiter() {
        store.seed(new ScrapeSnapshot(List.of(createUp2DateApplication()), START));
        when(versionRepository.scrape())
                .thenReturn(new ScrapeResult(List.of(createOldApplication()), 1, 0));
        lock.willAcquire(true);
        clock.advance(SCRAPE_INTERVAL);

        sut.getApplications();

        verify(versionRepository, times(1)).scrape();
        assertEquals(0, rateLimiter.tryAcquireCount,
                "the automatic staleness scrape must not spend a manual-budget slot");
    }

    @Test
    void getApplications_absentSnapshot_neverConsultsTheRateLimiter() {
        when(versionRepository.scrape())
                .thenReturn(new ScrapeResult(List.of(createOldApplication()), 1, 0));

        sut.getApplications();

        assertEquals(0, rateLimiter.tryAcquireCount,
                "scraping an absent snapshot must not spend a manual-budget slot");
    }

    private VersionApplication createOldApplication() {
        return new VersionApplication("Some-App", new Version("1.1.1"), new Version("2.2.2"));
    }

    private VersionApplication createUp2DateApplication() {
        return new VersionApplication("Another-app", new Version("2.2.2"), new Version("2.2.2"));
    }

    /**
     * Hand-written fake implementing the {@link ScrapeStateStore} port. Records writes so
     * tests can assert what the service persisted, and can be told to fail closed on read/write.
     */
    private static final class FakeScrapeStateStore implements ScrapeStateStore {
        private ScrapeSnapshot snapshot;
        private RuntimeException readFailure;
        private RuntimeException writeFailure;

        int writeCount;
        List<VersionApplication> lastWrittenApps;
        Instant lastWrittenAttemptAt;

        void seed(ScrapeSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        void failReadWith(RuntimeException ex) {
            this.readFailure = ex;
        }

        void failWriteWith(RuntimeException ex) {
            this.writeFailure = ex;
        }

        @Override
        public Optional<ScrapeSnapshot> read() {
            if (readFailure != null) {
                throw readFailure;
            }
            return Optional.ofNullable(snapshot);
        }

        @Override
        public void write(List<VersionApplication> applications, Instant attemptAt) {
            if (writeFailure != null) {
                throw writeFailure;
            }
            writeCount++;
            lastWrittenApps = applications;
            lastWrittenAttemptAt = attemptAt;
            snapshot = new ScrapeSnapshot(applications, attemptAt);
        }
    }

    /**
     * Hand-written fake implementing the {@link ScrapeLock} port. {@code willAcquire} models whether
     * this caller wins the cluster-wide lock; counters let tests assert the won/lost branches and the
     * fail-safe release. Defaults to granting the lock so the staleness-only tests above scrape as before.
     */
    private static final class FakeScrapeLock implements ScrapeLock {
        private boolean willAcquire = true;

        int acquireCount;
        int releaseCount;

        void willAcquire(boolean value) {
            this.willAcquire = value;
        }

        @Override
        public boolean tryAcquire() {
            acquireCount++;
            return willAcquire;
        }

        @Override
        public void release() {
            releaseCount++;
        }
    }

    /**
     * Hand-written fake implementing the {@link ScrapeRateLimiter} port. Here it only needs to count
     * {@code tryAcquire} calls so the automatic-scrape tests can assert the budget is never touched.
     */
    private static final class FakeScrapeRateLimiter implements ScrapeRateLimiter {
        int tryAcquireCount;

        @Override
        public Decision tryAcquire(Instant now) {
            tryAcquireCount++;
            return Decision.allowed(Integer.MAX_VALUE, 0);
        }

        @Override
        public Budget peek(Instant now) {
            return new Budget(Integer.MAX_VALUE, 0);
        }
    }

    /** A {@link Clock} whose instant can be advanced by hand to drive staleness checks. */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        private void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
