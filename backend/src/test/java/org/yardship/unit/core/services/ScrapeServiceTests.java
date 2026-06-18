package org.yardship.unit.core.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yardship.core.ports.in.Outcome;
import org.yardship.core.domain.primitives.ScrapeSnapshot;
import org.yardship.core.ports.in.ScrapeStatus;
import org.yardship.core.domain.primitives.Version;
import org.yardship.core.domain.primitives.VersionApplication;
import org.yardship.core.ports.out.ApplicationSources;
import org.yardship.core.ports.out.CurrentVersionSource;
import org.yardship.core.ports.out.LatestVersionSource;
import org.yardship.core.ports.out.ScrapeLock;
import org.yardship.core.ports.out.ScrapeRateLimiter;
import org.yardship.core.ports.out.ScrapeStateStore;
import org.yardship.core.ports.out.VersionSources;
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

/**
 * Unit tests for {@link ApplicationVersionService#triggerScrape()} — the manual, on-demand scrape.
 * A manual trigger BYPASSES the staleness check entirely: it always tries to acquire the
 * cluster-wide scrape lock.
 *
 * <ul>
 *   <li>Lock WON → scrape once (run the per-app loop over {@link VersionSources}), write a fresh
 *       snapshot (resetting the staleness clock), and return {@code SCRAPED} with counts assembled
 *       BY THE SERVICE from the loop ({@code appsSucceeded == appsAttempted - appsFailed}); release
 *       in a {@code finally}.</li>
 *   <li>Lock LOST → return {@code IN_PROGRESS}; no scrape, no write, no clock reset, no release.</li>
 * </ul>
 *
 * <p><b>Seam change:</b> the {@code attempted}/{@code failed} counts are no longer stubbed on a
 * mocked {@code VersionRepository.scrape()} — they are produced BY the service's loop over the
 * sources. These tests therefore drive the counts by seeding ok/throwing source doubles, which also
 * rehomes the old {@code ApplicationVersionClientIT} isolation+counts coverage to the unit level and
 * asserts the {@link org.yardship.core.ports.out.ScrapeResult} invariant
 * {@code applications.size() + failed == attempted}.
 */
class ScrapeServiceTests {

    private static final Duration SCRAPE_INTERVAL = Duration.ofHours(1);
    private static final Instant START = Instant.parse("2026-06-08T00:00:00Z");

    private FakeVersionSources sources;
    private FakeScrapeStateStore store;
    private FakeScrapeLock lock;
    private FakeScrapeRateLimiter rateLimiter;
    private MutableClock clock;
    private ApplicationVersionService sut;

    @BeforeEach
    void setUp() {
        sources = new FakeVersionSources();
        store = new FakeScrapeStateStore();
        lock = new FakeScrapeLock();
        rateLimiter = new FakeScrapeRateLimiter();
        clock = new MutableClock(START);
        sut = new ApplicationVersionService(sources, store, lock, rateLimiter, SCRAPE_INTERVAL, clock);
    }

    @Test
    void triggerScrape_lockWon_scrapesWritesAndReturnsScrapedWithCountsFromTheLoop() {
        // Three apps; one latest source throws → attempted=3, failed=1, succeeded=2.
        sources.seed(
                appSources("alpha", "1.0.0", "2.0.0"),
                new ApplicationSources("beta", okCurrent("1.0.0"), throwingLatest("github down")),
                appSources("gamma", "3.0.0", "4.0.0"));
        lock.willAcquire(true);

        ScrapeStatus status = sut.triggerScrape();

        assertEquals(Outcome.SCRAPED, status.outcome());
        assertEquals(3, status.appsAttempted());
        assertEquals(1, status.appsFailed());
        assertEquals(2, status.appsSucceeded(), "succeeded == attempted - failed");

        assertEquals(2, store.lastWrittenApps.size(), "the winner persists only the survivors");
        assertEquals(1, store.writeCount, "the winner must write a fresh snapshot");
        assertEquals(1, lock.acquireCount, "trigger must acquire the lock exactly once");
        assertEquals(1, lock.releaseCount, "the winner must release the lock");
    }

    @Test
    void triggerScrape_scrapeResultInvariant_holds_survivorsPlusFailedEqualsAttempted() {
        sources.seed(
                appSources("a", "1.0.0", "2.0.0"),
                new ApplicationSources("b", throwingCurrent("503"), okLatest("2.0.0")),
                new ApplicationSources("c", okCurrent("1.0.0"), throwingLatest("down")),
                appSources("d", "1.0.0", "2.0.0"));
        lock.willAcquire(true);

        ScrapeStatus status = sut.triggerScrape();

        assertEquals(4, status.appsAttempted());
        assertEquals(2, status.appsFailed());
        assertEquals(2, store.lastWrittenApps.size());
        assertEquals(status.appsAttempted(), store.lastWrittenApps.size() + status.appsFailed(),
                "ScrapeResult invariant: applications.size() + failed == attempted");
    }

    @Test
    void triggerScrape_noConfiguredApps_scrapesNothing_reportsZeroCounts() {
        sources.seedEmpty();
        lock.willAcquire(true);

        ScrapeStatus status = sut.triggerScrape();

        assertEquals(Outcome.SCRAPED, status.outcome());
        assertEquals(0, status.appsAttempted());
        assertEquals(0, status.appsFailed());
        assertEquals(0, status.appsSucceeded());
        assertTrue(store.lastWrittenApps.isEmpty());
    }

    @Test
    void triggerScrape_scraped_writesSnapshotWithAttemptAtFromClock() {
        sources.seed(appSources("Some-App", "1.1.1", "2.2.2"));
        lock.willAcquire(true);

        sut.triggerScrape();

        assertEquals(START, store.lastWrittenAttemptAt,
                "the written snapshot must carry lastAttemptAt == clock.instant() at trigger time");
    }

    @Test
    void triggerScrape_scraped_resetsStalenessClock_soSubsequentGetDoesNotReScrape() {
        sources.seed(appSources("Some-App", "1.1.1", "2.2.2"));
        lock.willAcquire(true);

        // Manual trigger writes a fresh snapshot at START.
        sut.triggerScrape();
        assertEquals(1, sources.readCount(), "trigger scrapes once");

        // Within the interval, a normal read must serve the fresh snapshot without re-scraping.
        clock.advance(SCRAPE_INTERVAL.minusSeconds(1));
        sut.getApplications();

        assertEquals(1, sources.readCount(), "a fresh manual snapshot must not be re-scraped by a subsequent read");
        assertEquals(1, store.writeCount, "a fresh manual snapshot must not be re-written by a subsequent read");
    }

    @Test
    void triggerScrape_bypassesStaleness_evenWhenSnapshotIsFresh() {
        // A snapshot attempted "just now" is FRESH — getApplications would serve it without scraping.
        // triggerScrape must scrape anyway.
        store.seed(new ScrapeSnapshot(List.of(createUp2DateApplication()), START));
        sources.seed(appSources("Some-App", "1.1.1", "2.2.2"));
        lock.willAcquire(true);

        ScrapeStatus status = sut.triggerScrape();

        assertEquals(Outcome.SCRAPED, status.outcome());
        assertEquals(1, sources.readCount(), "trigger must scrape even with a fresh snapshot");
        assertEquals(1, lock.acquireCount, "trigger must acquire the lock even with a fresh snapshot");
        assertEquals(1, store.writeCount);
    }

    @Test
    void triggerScrape_lockLost_returnsInProgress_withoutScrapingWritingOrReleasing() {
        store.seed(new ScrapeSnapshot(List.of(createUp2DateApplication()), START));
        sources.seed(appSources("Some-App", "1.1.1", "2.2.2"));
        lock.willAcquire(false);

        ScrapeStatus status = sut.triggerScrape();

        assertEquals(Outcome.IN_PROGRESS, status.outcome());
        assertEquals(0, sources.readCount(), "a loser must not scrape");
        assertEquals(0, store.writeCount, "a loser must not write");
        assertEquals(1, lock.acquireCount, "the loser still tries to acquire once");
        assertEquals(0, lock.releaseCount, "a loser holds nothing, so it must not release");
    }

    @Test
    void triggerScrape_lockLost_doesNotResetTheClock() {
        store.seed(new ScrapeSnapshot(List.of(createUp2DateApplication()), START));
        sources.seed(appSources("Some-App", "1.1.1", "2.2.2"));
        lock.willAcquire(false);

        sut.triggerScrape();

        assertEquals(0, store.writeCount, "IN_PROGRESS must not reset the staleness clock");
    }

    @Test
    void triggerScrape_lockWon_isolatesPerAppFailure_doesNotPropagate() {
        // Reframed: a per-app source failure is isolated inside the loop, so triggerScrape still
        // returns SCRAPED (with failed counted) — it does NOT throw. The lock is still released.
        sources.seed(
                new ApplicationSources("alpha", okCurrent("1.0.0"), throwingLatest("boom")),
                appSources("beta", "1.0.0", "2.0.0"));
        lock.willAcquire(true);

        ScrapeStatus status = sut.triggerScrape();

        assertEquals(Outcome.SCRAPED, status.outcome());
        assertEquals(2, status.appsAttempted());
        assertEquals(1, status.appsFailed());
        assertEquals(1, lock.releaseCount, "an isolated per-app failure must not leak the lock");
    }

    @Test
    void triggerScrape_lockWon_releasesEvenWhenWriteThrows() {
        // The remaining in-scrape throw that still escapes is the SNAPSHOT WRITE; the lock must
        // still be released. (Per-app source failures no longer propagate — see the isolation test.)
        lock.willAcquire(true);
        sources.seed(appSources("Some-App", "1.1.1", "2.2.2"));
        store.failWriteWith(new RuntimeException("valkey write failed"));

        assertThrows(RuntimeException.class, () -> sut.triggerScrape());

        assertEquals(1, lock.releaseCount, "the lock must be released even when the snapshot write fails");
    }

    // --- Scrape budget ------------------------------------------------------------------------
    //
    // LOCK-FIRST ordering, then budget: acquire the lock; if lost → IN_PROGRESS (limiter NEVER
    // consulted). If won, spend a slot via tryAcquire(clock.instant()): exhausted → RATE_LIMITED
    // (release the lock, no scrape, no write); allowed → scrape + write and return SCRAPED carrying
    // triggersRemaining and windowResetsInSeconds.

    @Test
    void triggerScrape_lockWon_budgetAllowed_scrapesAndCarriesBudgetTelemetry() {
        lock.willAcquire(true);
        rateLimiter.willAllow(7, 1800); // remaining=7, windowResetsIn=1800s
        sources.seed(
                appSources("alpha", "1.0.0", "2.0.0"),
                new ApplicationSources("beta", okCurrent("1.0.0"), throwingLatest("down")),
                appSources("gamma", "3.0.0", "4.0.0"));

        ScrapeStatus status = sut.triggerScrape();

        assertEquals(Outcome.SCRAPED, status.outcome());
        assertEquals(3, status.appsAttempted());
        assertEquals(1, status.appsFailed());
        assertEquals(2, status.appsSucceeded());
        assertEquals(7, status.triggersRemaining(), "triggersRemaining comes from the budget Decision");
        assertEquals(1800, status.windowResetsInSeconds(), "windowResetsInSeconds comes from the budget Decision");
        assertEquals(0, status.retryAfterSeconds(), "a SCRAPED outcome is not rate-limited");

        assertEquals(1, sources.readCount(), "a manual trigger scrapes once");
        assertEquals(1, store.writeCount, "the winner must write a fresh snapshot");
        assertEquals(1, rateLimiter.tryAcquireCount, "a manual trigger spends exactly one budget slot");
        assertEquals(1, lock.releaseCount, "the winner must release the lock");
    }

    @Test
    void triggerScrape_budgetExhausted_returnsRateLimited_withoutScrapingOrWriting() {
        lock.willAcquire(true);
        rateLimiter.willReject(42); // retryAfter=42s
        sources.seed(appSources("Some-App", "1.1.1", "2.2.2"));

        ScrapeStatus status = sut.triggerScrape();

        assertEquals(Outcome.RATE_LIMITED, status.outcome());
        assertEquals(42, status.retryAfterSeconds(), "retryAfterSeconds comes from the rejected Decision");
        assertEquals(0, sources.readCount(), "a rate-limited trigger must not scrape");
        assertEquals(0, store.writeCount, "a rate-limited trigger must not write");
        assertEquals(1, rateLimiter.tryAcquireCount, "the budget must be consulted before scraping");
        assertEquals(1, lock.releaseCount, "the lock acquired before the budget check must be released");
    }

    @Test
    void triggerScrape_lockLost_doesNotConsumeABudgetSlot() {
        store.seed(new ScrapeSnapshot(List.of(createUp2DateApplication()), START));
        sources.seed(appSources("Some-App", "1.1.1", "2.2.2"));
        lock.willAcquire(false);

        ScrapeStatus status = sut.triggerScrape();

        assertEquals(Outcome.IN_PROGRESS, status.outcome());
        assertEquals(0, rateLimiter.tryAcquireCount, "a lost lock must not consume a budget slot");
        assertEquals(0, sources.readCount());
        assertEquals(0, store.writeCount);
    }

    @Test
    void triggerScrape_passesDeterministicNowFromClockToTheLimiter() {
        lock.willAcquire(true);
        rateLimiter.willAllow(5, 600);
        sources.seed(appSources("Some-App", "1.1.1", "2.2.2"));

        sut.triggerScrape();

        assertEquals(START, rateLimiter.lastNow,
                "the budget must be evaluated against clock.instant(), not a wall clock");
    }

    // --- helpers ------------------------------------------------------------------------------

    private VersionApplication createUp2DateApplication() {
        return new VersionApplication("Another-app", new Version("2.2.2"), new Version("2.2.2"));
    }

    private ApplicationSources appSources(String name, String current, String latest) {
        return new ApplicationSources(name, okCurrent(current), okLatest(latest));
    }

    private CurrentVersionSource okCurrent(String value) {
        return () -> new Version(value);
    }

    private LatestVersionSource okLatest(String value) {
        return () -> new Version(value);
    }

    private CurrentVersionSource throwingCurrent(String message) {
        return () -> {
            throw new RuntimeException(message);
        };
    }

    private LatestVersionSource throwingLatest(String message) {
        return () -> {
            throw new RuntimeException(message);
        };
    }

    /**
     * Hand-written fake implementing the {@link VersionSources} port — the seam the scrape loop
     * runs over. Tests seed it with {@link ApplicationSources} pairs whose sources are lambda
     * doubles; {@link #readCount()} counts SCRAPE PASSES — it is incremented once per call to
     * {@link #applicationSources()}, which the service invokes exactly once per scrape pass — so
     * tests can assert whether (and how many times) the loop executed. Per-source lambdas do NOT
     * touch the counter, so reading both legs (current AND latest) of every app counts as one pass.
     */
    private static final class FakeVersionSources implements VersionSources {
        private List<ApplicationSources> apps = List.of();
        private int reads;

        void seed(ApplicationSources... apps) {
            this.apps = List.of(apps);
        }

        void seedEmpty() {
            this.apps = List.of();
        }

        int readCount() {
            return reads;
        }

        @Override
        public List<ApplicationSources> applicationSources() {
            reads++;
            return apps;
        }
    }

    /**
     * Hand-written fake implementing the {@link ScrapeStateStore} port. Records writes so tests can
     * assert what the service persisted, and can be told to fail closed on write.
     */
    private static final class FakeScrapeStateStore implements ScrapeStateStore {
        private ScrapeSnapshot snapshot;
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
     * Defaults to ALLOWED with a generous budget so the triggerScrape tests behave as before.
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
