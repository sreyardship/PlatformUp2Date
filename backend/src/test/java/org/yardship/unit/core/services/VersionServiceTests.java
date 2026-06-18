package org.yardship.unit.core.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yardship.core.domain.primitives.ScrapeSnapshot;
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
 * Unit tests for {@link ApplicationVersionService#getApplications()} against the Valkey-backed
 * scrape-state model AND the pluggable-source scrape loop (Issue 02).
 *
 * <p>The service holds NO in-memory cache: it reads a {@link ScrapeSnapshot} from the
 * {@link ScrapeStateStore} port, and when that snapshot is stale (or absent) it scrapes — running
 * the per-app loop over {@link VersionSources#applicationSources()}, isolating per-app failures —
 * and writes a fresh snapshot.
 *
 * <p>Staleness is evaluated against the snapshot's {@code lastAttemptAt}, the injected
 * {@link Clock}, and the configured scrape-interval:
 * {@code now - lastAttemptAt >= scrapeInterval} (or no snapshot at all).
 *
 * <p><b>Seam change from the old {@code VersionRepository} model:</b> the scrape loop now lives in
 * the service, driven by a {@link VersionSources} port that exposes per-app
 * {@link ApplicationSources} pairs. The store/lock/limiter/clock are the same hand-written fakes;
 * the sources are hand-written {@link CurrentVersionSource}/{@link LatestVersionSource} doubles
 * (returning a {@link Version} or throwing). A single source throwing is now ISOLATED inside the
 * loop and counted in {@code failed} — it does not propagate. The old
 * {@code lockWinner_releasesEvenWhenScrapeThrows} guarantee is therefore reframed: the
 * lock-release-on-throw path now hinges on the SNAPSHOT WRITE throwing (kept below), plus an
 * explicit isolation test proving a broken source is counted and the loop survives.
 */
class VersionServiceTests {

    private static final Duration SCRAPE_INTERVAL = Duration.ofHours(1);
    private static final Instant START = Instant.parse("2026-06-08T00:00:00Z");

    private FakeVersionSources sources;
    private FakeScrapeStateStore store;
    private FakeScrapeLock lock;
    private FakeScrapeRateLimiter rateLimiter;
    private FakeScrapeRateLimiter targetedRateLimiter;
    private MutableClock clock;
    private ApplicationVersionService sut;

    @BeforeEach
    void setUp() {
        sources = new FakeVersionSources();
        store = new FakeScrapeStateStore();
        lock = new FakeScrapeLock();
        rateLimiter = new FakeScrapeRateLimiter();
        targetedRateLimiter = new FakeScrapeRateLimiter();
        clock = new MutableClock(START);
        sut = new ApplicationVersionService(
                sources, store, lock, rateLimiter, targetedRateLimiter, SCRAPE_INTERVAL, clock);
    }

    @Test
    void getApplications_servesFreshSnapshot_withoutScrapingOrWriting() {
        VersionApplication app = createOldApplication();
        // A snapshot attempted "just now" is fresh — no scrape, no write expected.
        store.seed(new ScrapeSnapshot(List.of(app), START));
        sources.seed(appSources("Some-App", "1.1.1", "2.2.2"));

        List<VersionApplication> result = sut.getApplications();

        assertEquals(List.of(app), result);
        assertEquals(0, sources.readCount(), "a fresh snapshot must not read any source");
        assertEquals(0, store.writeCount, "fresh snapshot must not be re-written");
    }

    @Test
    void getApplications_servesFreshSnapshot_whenJustShyOfInterval() {
        VersionApplication app = createOldApplication();
        store.seed(new ScrapeSnapshot(List.of(app), START));
        sources.seed(appSources("Some-App", "1.1.1", "2.2.2"));
        clock.advance(SCRAPE_INTERVAL.minusSeconds(1));

        sut.getApplications();

        assertEquals(0, sources.readCount());
        assertEquals(0, store.writeCount);
    }

    @Test
    void getApplications_scrapesAndWrites_whenNoSnapshotExists() {
        sources.seed(appSources("Some-App", "1.1.1", "2.2.2"));

        List<VersionApplication> result = sut.getApplications();

        assertEquals(List.of(createOldApplication()), result);
        assertEquals(1, sources.readCount(), "an absent snapshot must trigger one scrape over the sources");
        assertEquals(1, store.writeCount, "an absent snapshot must trigger a write");
        assertEquals(List.of(createOldApplication()), store.lastWrittenApps);
        assertEquals(START, store.lastWrittenAttemptAt, "write must record the attempt instant from the clock");
    }

    @Test
    void getApplications_scrapesAndWrites_whenSnapshotIsStale() {
        store.seed(new ScrapeSnapshot(List.of(createUp2DateApplication()), START));
        sources.seed(appSources("Some-App", "1.1.1", "2.2.2"));

        clock.advance(SCRAPE_INTERVAL);
        List<VersionApplication> result = sut.getApplications();

        assertEquals(List.of(createOldApplication()), result);
        assertEquals(1, sources.readCount());
        assertEquals(1, store.writeCount);
        assertEquals(START.plus(SCRAPE_INTERVAL), store.lastWrittenAttemptAt);
    }

    @Test
    void getApplications_returnsScrapedApps_evenWhenEmpty() {
        // No configured apps → the scrape attempts nothing and the result is empty.
        sources.seedEmpty();

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
        assertEquals(0, sources.readCount());
    }

    @Test
    void getApplications_propagatesStoreWriteFailure_failClosed() {
        // No snapshot → scrape succeeds → write to Valkey fails → must propagate, not swallow.
        sources.seed(appSources("Some-App", "1.1.1", "2.2.2"));
        store.failWriteWith(new RuntimeException("valkey write failed"));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> sut.getApplications());
        assertEquals("valkey write failed", ex.getMessage());
    }

    // --- Per-app failure isolation INSIDE the scrape loop (Issue 02) --------------------------
    //
    // The scrape loop moved into the service, so a single source throwing is caught and counted in
    // ScrapeResult.failed — it does NOT propagate. This rehomes the old ApplicationVersionClientIT
    // isolation coverage to the unit level.

    @Test
    void getApplications_isolatesPerAppFailure_oneBrokenSourceDoesNotAbortTheScrape() {
        // Three apps; the middle one's latest source throws. The loop completes, the broken app is
        // counted in failed and excluded, the survivors are returned, and the lock is released.
        sources.seed(
                appSources("alpha", "1.0.0", "2.0.0"),
                new ApplicationSources("beta", okCurrent("1.0.0"), throwingLatest("github down")),
                appSources("gamma", "3.0.0", "4.0.0"));

        List<VersionApplication> result = sut.getApplications();

        assertEquals(2, result.size(), "only the survivors are returned");
        assertEquals("alpha", result.get(0).name());
        assertEquals("gamma", result.get(1).name());
        assertEquals(1, store.writeCount, "the survivors are still persisted");
        assertEquals(1, lock.releaseCount, "an isolated per-app failure must NOT leak the lock");
    }

    @Test
    void getApplications_brokenCurrentSource_isAlsoIsolatedAndCounted() {
        sources.seed(
                new ApplicationSources("alpha", throwingCurrent("endpoint 503"), okLatest("2.0.0")),
                appSources("beta", "1.0.0", "2.0.0"));

        List<VersionApplication> result = sut.getApplications();

        assertEquals(1, result.size());
        assertEquals("beta", result.get(0).name());
    }

    @Test
    void getApplications_allSourcesBroken_writesEmptyAndReleasesLock() {
        sources.seed(
                new ApplicationSources("alpha", throwingCurrent("down"), okLatest("2.0.0")),
                new ApplicationSources("beta", okCurrent("1.0.0"), throwingLatest("down")));

        List<VersionApplication> result = sut.getApplications();

        assertTrue(result.isEmpty());
        assertEquals(1, store.writeCount, "an all-failed scrape still records an (empty) attempt");
        assertEquals(1, lock.releaseCount);
    }

    // --- Distributed scrape lock --------------------------------------------------------------
    //
    // The lock guards the SCRAPE branch only. On a stale (or absent) snapshot a caller tries to
    // acquire the cluster-wide lock: the winner scrapes + writes + releases; the losers serve the
    // current shared snapshot WITHOUT hitting upstreams. A fresh snapshot never touches the lock.

    @Test
    void getApplications_freshSnapshot_neverTouchesTheLock() {
        store.seed(new ScrapeSnapshot(List.of(createOldApplication()), START));
        sources.seed(appSources("Some-App", "1.1.1", "2.2.2"));

        sut.getApplications();

        assertEquals(0, lock.acquireCount, "a fresh snapshot must be served without acquiring the lock");
        assertEquals(0, lock.releaseCount);
        assertEquals(0, sources.readCount());
    }

    @Test
    void getApplications_staleSnapshot_lockWon_scrapesWritesAndReleases() {
        store.seed(new ScrapeSnapshot(List.of(createUp2DateApplication()), START));
        sources.seed(appSources("Some-App", "1.1.1", "2.2.2"));
        lock.willAcquire(true);
        clock.advance(SCRAPE_INTERVAL);

        List<VersionApplication> result = sut.getApplications();

        assertEquals(List.of(createOldApplication()), result, "the lock winner returns the freshly scraped apps");
        assertEquals(1, lock.acquireCount, "the winner must try to acquire exactly once");
        assertEquals(1, sources.readCount());
        assertEquals(1, store.writeCount, "the winner must write the new snapshot");
        assertEquals(1, lock.releaseCount, "the winner must release the lock");
    }

    @Test
    void getApplications_staleSnapshot_lockLost_servesSnapshotWithoutScraping() {
        VersionApplication shared = createUp2DateApplication();
        store.seed(new ScrapeSnapshot(List.of(shared), START));
        sources.seed(appSources("Some-App", "1.1.1", "2.2.2"));
        lock.willAcquire(false);
        clock.advance(SCRAPE_INTERVAL);

        List<VersionApplication> result = sut.getApplications();

        assertEquals(List.of(shared), result, "a loser serves the current shared snapshot");
        assertEquals(0, sources.readCount());
        assertEquals(0, store.writeCount, "a loser must not write");
        assertEquals(0, lock.releaseCount, "a loser holds nothing, so it must not release");
    }

    @Test
    void getApplications_lockLost_withNoSnapshotYet_servesEmptyWithoutScraping() {
        // Edge case: stale (here: absent) snapshot, lock lost, and nothing has ever been written.
        // The loser must NOT scrape; it serves the last-known result, which is empty.
        sources.seed(appSources("Some-App", "1.1.1", "2.2.2"));
        lock.willAcquire(false);

        List<VersionApplication> result = sut.getApplications();

        assertTrue(result.isEmpty(), "no snapshot + lost lock serves an empty last-known result");
        assertEquals(0, sources.readCount());
        assertEquals(0, store.writeCount);
    }

    @Test
    void getApplications_lockWinner_releasesEvenWhenWriteThrows() {
        // Reframed lock-release-on-throw guarantee: per-app source failures are now isolated and do
        // NOT propagate, so the only in-scrape throw that still escapes is the SNAPSHOT WRITE. The
        // lock must still be released in that case.
        lock.willAcquire(true);
        sources.seed(appSources("Some-App", "1.1.1", "2.2.2"));
        store.failWriteWith(new RuntimeException("valkey write failed"));

        assertThrows(RuntimeException.class, () -> sut.getApplications());

        assertEquals(1, lock.releaseCount, "the lock must be released even when the snapshot write fails");
    }

    // --- Scrape budget is manual-only ---------------------------------------------------------
    //
    // The rolling-window budget caps MANUAL triggers (triggerScrape) only. The automatic staleness
    // scrape on getApplications must NEVER consult the rate limiter.

    @Test
    void getApplications_staleSnapshot_neverConsultsTheRateLimiter() {
        store.seed(new ScrapeSnapshot(List.of(createUp2DateApplication()), START));
        sources.seed(appSources("Some-App", "1.1.1", "2.2.2"));
        lock.willAcquire(true);
        clock.advance(SCRAPE_INTERVAL);

        sut.getApplications();

        assertEquals(1, sources.readCount());
        assertEquals(0, rateLimiter.tryAcquireCount,
                "the automatic staleness scrape must not spend a manual-budget slot");
        assertEquals(0, targetedRateLimiter.tryAcquireCount,
                "the automatic staleness scrape must not spend a targeted-budget slot either");
    }

    @Test
    void getApplications_absentSnapshot_neverConsultsTheRateLimiter() {
        sources.seed(appSources("Some-App", "1.1.1", "2.2.2"));

        sut.getApplications();

        assertEquals(0, rateLimiter.tryAcquireCount,
                "scraping an absent snapshot must not spend a manual-budget slot");
        assertEquals(0, targetedRateLimiter.tryAcquireCount,
                "scraping an absent snapshot must not spend a targeted-budget slot either");
    }

    // --- helpers ------------------------------------------------------------------------------

    private VersionApplication createOldApplication() {
        return new VersionApplication("Some-App", new Version("1.1.1"), new Version("2.2.2"));
    }

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
     * staleness/lock tests can assert whether the loop actually executed (and how many times)
     * without resorting to a Mockito verify on a deleted port. Per-source lambdas do NOT touch the
     * counter, so reading both legs (current AND latest) of every app still counts as one pass.
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
