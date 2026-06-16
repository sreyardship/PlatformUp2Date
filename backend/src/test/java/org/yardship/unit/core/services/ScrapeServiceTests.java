package org.yardship.unit.core.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yardship.core.ports.in.Outcome;
import org.yardship.core.ports.out.ScrapeResult;
import org.yardship.core.domain.primitives.ScrapeSnapshot;
import org.yardship.core.ports.in.ScrapeStatus;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ApplicationVersionService#triggerScrape()} — the manual, on-demand scrape
 * (Issue 03). A manual trigger BYPASSES the staleness check entirely: it always tries to acquire the
 * cluster-wide scrape lock.
 *
 * <ul>
 *   <li>Lock WON → scrape once, write a fresh snapshot (which resets the staleness clock to
 *       {@code clock.instant()} at trigger time), and return {@code SCRAPED} with counts mapped from
 *       the {@link ScrapeResult} ({@code appsSucceeded == appsAttempted - appsFailed}); release in a
 *       {@code finally}.</li>
 *   <li>Lock LOST → return {@code IN_PROGRESS}; no scrape, no write, no clock reset, no release
 *       (the loser holds nothing).</li>
 * </ul>
 *
 * <p>Reuses the same hand-written {@code FakeScrapeStateStore} / {@code FakeScrapeLock} / Mockito repo
 * / {@code MutableClock} seams as {@code VersionServiceTests}.
 */
class ScrapeServiceTests {

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
    void triggerScrape_lockWon_scrapesWritesAndReturnsScrapedWithMappedCounts() {
        // attempted=3, failed=1 → succeeded=2.
        when(versionRepository.scrape())
                .thenReturn(new ScrapeResult(List.of(createOldApplication(), createUp2DateApplication()), 3, 1));
        lock.willAcquire(true);

        ScrapeStatus status = sut.triggerScrape();

        assertEquals(Outcome.SCRAPED, status.outcome());
        assertEquals(3, status.appsAttempted());
        assertEquals(1, status.appsFailed());
        assertEquals(2, status.appsSucceeded(), "succeeded == attempted - failed");

        verify(versionRepository, times(1)).scrape();
        assertEquals(1, store.writeCount, "the winner must write a fresh snapshot");
        assertEquals(1, lock.acquireCount, "trigger must acquire the lock exactly once");
        assertEquals(1, lock.releaseCount, "the winner must release the lock");
    }

    @Test
    void triggerScrape_scraped_writesSnapshotWithAttemptAtFromClock() {
        when(versionRepository.scrape())
                .thenReturn(new ScrapeResult(List.of(createOldApplication()), 1, 0));
        lock.willAcquire(true);

        sut.triggerScrape();

        assertEquals(START, store.lastWrittenAttemptAt,
                "the written snapshot must carry lastAttemptAt == clock.instant() at trigger time");
    }

    @Test
    void triggerScrape_scraped_resetsStalenessClock_soSubsequentGetDoesNotReScrape() {
        when(versionRepository.scrape())
                .thenReturn(new ScrapeResult(List.of(createOldApplication()), 1, 0));
        lock.willAcquire(true);

        // Manual trigger writes a fresh snapshot at START.
        sut.triggerScrape();
        verify(versionRepository, times(1)).scrape();

        // Within the interval, a normal read must serve the fresh snapshot without re-scraping.
        clock.advance(SCRAPE_INTERVAL.minusSeconds(1));
        sut.getApplications();

        verify(versionRepository, times(1)).scrape();
        assertEquals(1, store.writeCount, "a fresh manual snapshot must not be re-written by a subsequent read");
    }

    @Test
    void triggerScrape_bypassesStaleness_evenWhenSnapshotIsFresh() {
        // A snapshot attempted "just now" is FRESH — getApplications would serve it without scraping.
        // triggerScrape must scrape anyway.
        store.seed(new ScrapeSnapshot(List.of(createUp2DateApplication()), START));
        when(versionRepository.scrape())
                .thenReturn(new ScrapeResult(List.of(createOldApplication()), 1, 0));
        lock.willAcquire(true);

        ScrapeStatus status = sut.triggerScrape();

        assertEquals(Outcome.SCRAPED, status.outcome());
        verify(versionRepository, times(1)).scrape();
        assertEquals(1, lock.acquireCount, "trigger must acquire the lock even with a fresh snapshot");
        assertEquals(1, store.writeCount);
    }

    @Test
    void triggerScrape_lockLost_returnsInProgress_withoutScrapingWritingOrReleasing() {
        store.seed(new ScrapeSnapshot(List.of(createUp2DateApplication()), START));
        lock.willAcquire(false);

        ScrapeStatus status = sut.triggerScrape();

        assertEquals(Outcome.IN_PROGRESS, status.outcome());
        verify(versionRepository, never()).scrape();
        assertEquals(0, store.writeCount, "a loser must not write");
        assertEquals(1, lock.acquireCount, "the loser still tries to acquire once");
        assertEquals(0, lock.releaseCount, "a loser holds nothing, so it must not release");
    }

    @Test
    void triggerScrape_lockLost_doesNotResetTheClock() {
        // A fresh snapshot at START; lock lost → no write → the staleness clock is untouched.
        store.seed(new ScrapeSnapshot(List.of(createUp2DateApplication()), START));
        lock.willAcquire(false);

        sut.triggerScrape();

        assertEquals(0, store.writeCount, "IN_PROGRESS must not reset the staleness clock");
    }

    @Test
    void triggerScrape_lockWon_releasesEvenWhenScrapeThrows() {
        lock.willAcquire(true);
        when(versionRepository.scrape()).thenThrow(new RuntimeException("scrape failed"));

        assertThrows(RuntimeException.class, () -> sut.triggerScrape());

        assertEquals(1, lock.releaseCount, "the lock must be released even when the scrape blows up");
    }

    @Test
    void triggerScrape_lockWon_releasesEvenWhenWriteThrows() {
        lock.willAcquire(true);
        when(versionRepository.scrape())
                .thenReturn(new ScrapeResult(List.of(createOldApplication()), 1, 0));
        store.failWriteWith(new RuntimeException("valkey write failed"));

        assertThrows(RuntimeException.class, () -> sut.triggerScrape());

        assertEquals(1, lock.releaseCount, "the lock must be released even when the snapshot write fails");
    }

    // --- Scrape budget (Issue 04) -------------------------------------------------------------
    //
    // A manual trigger is gated by a shared rolling-window budget. The chosen ordering is LOCK-FIRST,
    // then budget: acquire the cluster-wide lock; if lost → IN_PROGRESS (the limiter is NEVER consulted,
    // so a lost trigger consumes no slot — no refund needed). If won, spend a budget slot via
    // tryAcquire(clock.instant()): if exhausted → RATE_LIMITED (release the lock, no scrape, no write,
    // carrying retryAfterSeconds from the Decision); if allowed → scrape + write and return SCRAPED
    // carrying triggersRemaining (Decision.remaining) and windowResetsInSeconds (Decision.windowResetsIn).

    @Test
    void triggerScrape_lockWon_budgetAllowed_scrapesAndCarriesBudgetTelemetry() {
        lock.willAcquire(true);
        rateLimiter.willAllow(7, 1800); // remaining=7, windowResetsIn=1800s
        when(versionRepository.scrape())
                .thenReturn(new ScrapeResult(List.of(createOldApplication(), createUp2DateApplication()), 3, 1));

        ScrapeStatus status = sut.triggerScrape();

        assertEquals(Outcome.SCRAPED, status.outcome());
        assertEquals(3, status.appsAttempted());
        assertEquals(1, status.appsFailed());
        assertEquals(2, status.appsSucceeded());
        assertEquals(7, status.triggersRemaining(), "triggersRemaining comes from the budget Decision");
        assertEquals(1800, status.windowResetsInSeconds(), "windowResetsInSeconds comes from the budget Decision");
        assertEquals(0, status.retryAfterSeconds(), "a SCRAPED outcome is not rate-limited");

        verify(versionRepository, times(1)).scrape();
        assertEquals(1, store.writeCount, "the winner must write a fresh snapshot");
        assertEquals(1, rateLimiter.tryAcquireCount, "a manual trigger spends exactly one budget slot");
        assertEquals(1, lock.releaseCount, "the winner must release the lock");
    }

    @Test
    void triggerScrape_budgetExhausted_returnsRateLimited_withoutScrapingOrWriting() {
        lock.willAcquire(true);
        rateLimiter.willReject(42); // retryAfter=42s

        ScrapeStatus status = sut.triggerScrape();

        assertEquals(Outcome.RATE_LIMITED, status.outcome());
        assertEquals(42, status.retryAfterSeconds(), "retryAfterSeconds comes from the rejected Decision");
        verify(versionRepository, never()).scrape();
        assertEquals(0, store.writeCount, "a rate-limited trigger must not write");
        assertEquals(1, rateLimiter.tryAcquireCount, "the budget must be consulted before scraping");
        assertEquals(1, lock.releaseCount, "the lock acquired before the budget check must be released");
    }

    @Test
    void triggerScrape_lockLost_doesNotConsumeABudgetSlot() {
        // Lock-first ordering: a loser returns IN_PROGRESS and must NEVER touch the limiter, so no slot
        // is spent (and no refund is needed).
        store.seed(new ScrapeSnapshot(List.of(createUp2DateApplication()), START));
        lock.willAcquire(false);

        ScrapeStatus status = sut.triggerScrape();

        assertEquals(Outcome.IN_PROGRESS, status.outcome());
        assertEquals(0, rateLimiter.tryAcquireCount, "a lost lock must not consume a budget slot");
        verify(versionRepository, never()).scrape();
        assertEquals(0, store.writeCount);
    }

    @Test
    void triggerScrape_passesDeterministicNowFromClockToTheLimiter() {
        lock.willAcquire(true);
        rateLimiter.willAllow(5, 600);
        when(versionRepository.scrape())
                .thenReturn(new ScrapeResult(List.of(createOldApplication()), 1, 0));

        sut.triggerScrape();

        assertEquals(START, rateLimiter.lastNow,
                "the budget must be evaluated against clock.instant(), not a wall clock");
    }

    private VersionApplication createOldApplication() {
        return new VersionApplication("Some-App", new Version("1.1.1"), new Version("2.2.2"));
    }

    private VersionApplication createUp2DateApplication() {
        return new VersionApplication("Another-app", new Version("2.2.2"), new Version("2.2.2"));
    }

    /**
     * Hand-written fake implementing the {@link ScrapeStateStore} port. Records writes so tests can
     * assert what the service persisted, and can be told to fail closed on read/write.
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
     * fail-safe release.
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
     * Hand-written fake implementing the {@link ScrapeRateLimiter} port. Models the budget decision
     * (allowed + remaining/windowResetsIn, or rejected + retryAfter), counts {@code tryAcquire} calls,
     * and records the {@code now} it was handed so tests can assert the deterministic clock is used.
     * Defaults to ALLOWED with a generous budget so the slice-03 triggerScrape tests behave as before.
     */
    private static final class FakeScrapeRateLimiter implements ScrapeRateLimiter {
        private Decision next = Decision.allowed(Integer.MAX_VALUE, 0);

        int tryAcquireCount;
        Instant lastNow;

        void willAllow(int remaining, long windowResetsIn) {
            this.next = Decision.allowed(remaining, windowResetsIn);
        }

        void willReject(long retryAfter) {
            this.next = Decision.rejected(retryAfter);
        }

        @Override
        public Decision tryAcquire(Instant now) {
            tryAcquireCount++;
            lastNow = now;
            return next;
        }

        @Override
        public Budget peek(Instant now) {
            return new Budget(next.remaining(), next.windowResetsIn());
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
