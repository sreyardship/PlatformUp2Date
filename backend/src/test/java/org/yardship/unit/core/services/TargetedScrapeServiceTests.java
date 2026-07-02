package org.yardship.unit.core.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yardship.core.domain.primitives.ScrapeSnapshot;
import org.yardship.core.domain.primitives.ScrapeTarget;
import org.yardship.core.domain.primitives.Side;
import org.yardship.core.domain.primitives.SideObservation;
import org.yardship.core.domain.primitives.TargetResult;
import org.yardship.core.domain.primitives.SemverVersion;
import org.yardship.core.domain.primitives.VersionValue;
import org.yardship.core.domain.primitives.VersionApplication;
import org.yardship.core.ports.in.Outcome;
import org.yardship.core.ports.in.ScrapeStatus;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ApplicationVersionService#targetedScrape(List)} — a scrape limited to a
 * caller-chosen set of {@code (app, side)} targets, instead of the whole fleet.
 *
 * <p>Mirrors the fake-based style of {@link ScrapeServiceTests}: hand-written fakes, no CDI/Mockito,
 * a {@link MutableClock} the service test-visible constructor takes explicitly.
 */
class TargetedScrapeServiceTests {

    private static final Duration SCRAPE_INTERVAL = Duration.ofHours(1);
    private static final Instant START = Instant.parse("2026-06-08T00:00:00Z");

    private FakeVersionSources sources;
    private FakeScrapeStateStore store;
    private FakeScrapeLock lock;
    private FakeScrapeRateLimiter fullRateLimiter;
    private FakeScrapeRateLimiter rateLimiter;
    private MutableClock clock;
    private ApplicationVersionService sut;

    @BeforeEach
    void setUp() {
        sources = new FakeVersionSources();
        store = new FakeScrapeStateStore();
        lock = new FakeScrapeLock();
        fullRateLimiter = new FakeScrapeRateLimiter();
        rateLimiter = new FakeScrapeRateLimiter();
        clock = new MutableClock(START);
        sut = new ApplicationVersionService(sources, store, lock, fullRateLimiter, rateLimiter, SCRAPE_INTERVAL, clock);
    }

    @Test
    void targetedScrape_validTargets_returnsScrapedWithOneTargetResultPerTarget() {
        store.seed(snapshotOf(
                new VersionApplication("argo-cd", SideObservation.resolved(new SemverVersion("1.0.0"), START), SideObservation.resolved(new SemverVersion("1.0.0"), START)),
                new VersionApplication("grafana", SideObservation.resolved(new SemverVersion("2.0.0"), START), SideObservation.resolved(new SemverVersion("2.0.0"), START))));
        sources.seed(
                appSources("argo-cd", "1.1.0", "1.1.0"),
                appSources("grafana", "2.0.0", "2.1.0"));
        lock.willAcquire(true);

        ScrapeStatus status = sut.targetedScrape(List.of(
                new ScrapeTarget("argo-cd", Side.CURRENT),
                new ScrapeTarget("grafana", Side.LATEST)));

        assertEquals(Outcome.SCRAPED, status.outcome());
        assertEquals(2, status.targetResults().size());
        assertTrue(status.targetResults().stream().allMatch(TargetResult::succeeded));
    }

    @Test
    void targetedScrape_currentOnlyTarget_updatesCurrent_leavesLatestUnchanged() {
        store.seed(snapshotOf(
                new VersionApplication("argo-cd", SideObservation.resolved(new SemverVersion("1.0.0"), START), SideObservation.resolved(new SemverVersion("9.9.9"), START))));
        sources.seed(appSources("argo-cd", "1.5.0", "9.9.9"));
        lock.willAcquire(true);

        sut.targetedScrape(List.of(new ScrapeTarget("argo-cd", Side.CURRENT)));

        VersionApplication written = onlyApp(store.lastWrittenApps, "argo-cd");
        assertEquals("1.5.0", written.current().value().orElseThrow().value());
        assertEquals("9.9.9", written.latest().value().orElseThrow().value(), "latest must be left exactly as the snapshot had it");
    }

    @Test
    void targetedScrape_latestOnlyTarget_updatesLatest_leavesCurrentUnchanged() {
        store.seed(snapshotOf(
                new VersionApplication("argo-cd", SideObservation.resolved(new SemverVersion("1.0.0"), START), SideObservation.resolved(new SemverVersion("1.0.0"), START))));
        sources.seed(appSources("argo-cd", "1.0.0", "2.0.0"));
        lock.willAcquire(true);

        sut.targetedScrape(List.of(new ScrapeTarget("argo-cd", Side.LATEST)));

        VersionApplication written = onlyApp(store.lastWrittenApps, "argo-cd");
        assertEquals("1.0.0", written.current().value().orElseThrow().value(), "current must be left exactly as the snapshot had it");
        assertEquals("2.0.0", written.latest().value().orElseThrow().value());
    }

    @Test
    void targetedScrape_bothTarget_updatesBothSides() {
        store.seed(snapshotOf(
                new VersionApplication("argo-cd", SideObservation.resolved(new SemverVersion("1.0.0"), START), SideObservation.resolved(new SemverVersion("1.0.0"), START))));
        sources.seed(appSources("argo-cd", "1.5.0", "2.0.0"));
        lock.willAcquire(true);

        sut.targetedScrape(List.of(new ScrapeTarget("argo-cd", Side.BOTH)));

        VersionApplication written = onlyApp(store.lastWrittenApps, "argo-cd");
        assertEquals("1.5.0", written.current().value().orElseThrow().value());
        assertEquals("2.0.0", written.latest().value().orElseThrow().value());
    }

    @Test
    void targetedScrape_overExistingSnapshot_doesNotAdvanceLastAttemptAt() {
        store.seed(new ScrapeSnapshot(
                List.of(new VersionApplication("argo-cd", SideObservation.resolved(new SemverVersion("1.0.0"), START), SideObservation.resolved(new SemverVersion("1.0.0"), START))),
                START.minus(Duration.ofMinutes(10))));
        sources.seed(appSources("argo-cd", "1.1.0", "1.0.0"));
        lock.willAcquire(true);
        clock.advance(Duration.ofMinutes(10)); // "now" has moved on from when the snapshot was written

        sut.targetedScrape(List.of(new ScrapeTarget("argo-cd", Side.CURRENT)));

        assertEquals(START.minus(Duration.ofMinutes(10)), store.lastWrittenAttemptAt,
                "a targeted scrape must re-supply the snapshot's existing lastAttemptAt, "
                        + "not the current clock instant, so the fleet-wide staleness clock is not advanced");
    }

    @Test
    void targetedScrape_singleSideTarget_appAbsentFromSnapshot_readsOnlyRequestedSide_otherSidePending() {
        // Issue 03: DROP the effectiveSide upgrade workaround. A cold single-side target no longer
        // upgrades to BOTH. Instead it reads only the requested side and persists the app with the
        // other side as pending (no value). The VersionApplication is Unresolved.
        store.seed(snapshotOf(
                new VersionApplication("grafana", SideObservation.resolved(new SemverVersion("2.0.0"), START), SideObservation.resolved(new SemverVersion("2.0.0"), START))));
        sources.seed(
                appSources("grafana", "2.0.0", "2.0.0"),
                appSources("argo-cd", "1.0.0", "1.2.0")); // cold-start: not yet in the snapshot
        lock.willAcquire(true);

        ScrapeStatus status = sut.targetedScrape(List.of(new ScrapeTarget("argo-cd", Side.CURRENT)));

        TargetResult result = onlyResult(status.targetResults(), "argo-cd");
        assertTrue(result.succeeded(), "the CURRENT side was requested and resolved successfully");
        assertEquals(Side.CURRENT, result.side(),
                "cold-start must report the REQUESTED side (CURRENT), not upgrade to BOTH");

        VersionApplication written = onlyApp(store.lastWrittenApps, "argo-cd");
        assertEquals("1.0.0", written.current().value().orElseThrow().value(),
                "current side (the requested one) must be resolved");
        assertTrue(written.latest().value().isEmpty(),
                "latest side was not requested and has no prior → must remain pending (no value)");
        assertFalse(written.latest().isResolved(),
                "the latest side must be Unresolved (no value) because it was never requested or prior-known");
    }

    @Test
    void targetedScrape_singleSideTarget_appAbsentFromSnapshot_latestSide_currentRemainsPending() {
        // Symmetric: targeting LATEST for a cold app → current remains pending.
        store.seedEmpty();
        sources.seed(appSources("new-app", "1.0.0", "2.0.0"));
        lock.willAcquire(true);

        sut.targetedScrape(List.of(new ScrapeTarget("new-app", Side.LATEST)));

        VersionApplication written = onlyApp(store.lastWrittenApps, "new-app");
        assertEquals("2.0.0", written.latest().value().orElseThrow().value(),
                "latest side (the requested one) must be resolved");
        assertTrue(written.current().value().isEmpty(),
                "current side was not requested and has no prior → must remain pending");
        assertFalse(written.current().isResolved(), "the current side must be Unresolved (no value)");
    }

    @Test
    void targetedScrape_emptySnapshot_writesDefinitelyStaleAttemptAt_soNextPlainReadStillScrapes() {
        store.seedEmpty();
        sources.seed(appSources("argo-cd", "1.0.0", "1.2.0"));
        lock.willAcquire(true);

        sut.targetedScrape(List.of(new ScrapeTarget("argo-cd", Side.CURRENT)));

        Duration sinceWritten = Duration.between(store.lastWrittenAttemptAt, clock.instant());
        assertTrue(sinceWritten.compareTo(SCRAPE_INTERVAL) >= 0,
                "an empty-snapshot cold start must write a lastAttemptAt stale enough that a subsequent "
                        + "plain read still triggers a full scrape, got sinceWritten=" + sinceWritten);
    }

    @Test
    void targetedScrape_unmonitoredApp_failsItsOwnTarget_withoutSinkingOthers() {
        store.seedEmpty();
        sources.seed(appSources("argo-cd", "1.0.0", "1.2.0"));
        lock.willAcquire(true);

        ScrapeStatus status = sut.targetedScrape(List.of(
                new ScrapeTarget("argo-cd", Side.BOTH),
                new ScrapeTarget("nonexistent-app", Side.BOTH)));

        TargetResult unmonitored = onlyResult(status.targetResults(), "nonexistent-app");
        assertFalse(unmonitored.succeeded());
        assertEquals("not monitored", unmonitored.reason());

        TargetResult known = onlyResult(status.targetResults(), "argo-cd");
        assertTrue(known.succeeded(), "the unmonitored target must not sink the rest of the batch");
    }

    @Test
    void targetedScrape_lockLost_returnsInProgress_withoutSpendingBudgetOrWriting() {
        store.seedEmpty();
        sources.seed(appSources("argo-cd", "1.0.0", "1.2.0"));
        lock.willAcquire(false);

        ScrapeStatus status = sut.targetedScrape(List.of(new ScrapeTarget("argo-cd", Side.BOTH)));

        assertEquals(Outcome.IN_PROGRESS, status.outcome());
        assertEquals(0, rateLimiter.tryAcquireCount, "a lost lock must not consume a budget slot");
        assertEquals(0, fullRateLimiter.tryAcquireCount, "a lost lock must not consume any budget slot");
        assertEquals(0, store.writeCount, "a lost lock must not write");
        assertEquals(1, lock.acquireCount);
        assertEquals(0, lock.releaseCount, "a loser holds nothing, so it must not release");
    }

    @Test
    void targetedScrape_budgetExhausted_returnsRateLimited_releasesLock_doesNotWrite() {
        store.seedEmpty();
        sources.seed(appSources("argo-cd", "1.0.0", "1.2.0"));
        lock.willAcquire(true);
        rateLimiter.willReject(17);

        ScrapeStatus status = sut.targetedScrape(List.of(new ScrapeTarget("argo-cd", Side.BOTH)));

        assertEquals(Outcome.RATE_LIMITED, status.outcome());
        assertEquals(17, status.retryAfterSeconds());
        assertEquals(0, store.writeCount, "a rate-limited targeted scrape must not write");
        assertEquals(1, lock.releaseCount, "the lock acquired before the budget check must be released");
        assertEquals(0, fullRateLimiter.tryAcquireCount, "a targeted scrape must never spend the full budget");
    }

    // --- Issue 03: targeted scrapes spend ONLY their own budget -------------------------------

    @Test
    void targetedScrape_lockWon_spendsOnlyTheTargetedBudget_neverTheFullBudget() {
        store.seedEmpty();
        sources.seed(appSources("argo-cd", "1.0.0", "1.2.0"));
        lock.willAcquire(true);

        sut.targetedScrape(List.of(new ScrapeTarget("argo-cd", Side.BOTH)));

        assertEquals(1, rateLimiter.tryAcquireCount, "the targeted path must spend from the targeted budget");
        assertEquals(0, fullRateLimiter.tryAcquireCount,
                "the targeted path must never touch the full-scrape budget");
    }

    @Test
    void targetedScrape_budgetTelemetry_reflectsTheTargetedBudgetNotTheFullBudget() {
        store.seedEmpty();
        sources.seed(appSources("argo-cd", "1.0.0", "1.2.0"));
        lock.willAcquire(true);
        rateLimiter.willAllow(23, 900); // targeted budget's own remaining/reset
        fullRateLimiter.willAllow(1, 3599); // a different value the assertion must NOT pick up

        ScrapeStatus status = sut.targetedScrape(List.of(new ScrapeTarget("argo-cd", Side.BOTH)));

        assertEquals(23, status.triggersRemaining(),
                "a targeted scrape's reported remaining must come from the TARGETED budget");
        assertEquals(900, status.windowResetsInSeconds(),
                "a targeted scrape's reported reset time must come from the TARGETED budget");
    }

    @Test
    void targetedScrape_oneSourceThrows_isolatesToThatTarget_othersStillLand() {
        store.seedEmpty();
        sources.seed(
                appSources("argo-cd", "1.0.0", "1.2.0"),
                new ApplicationSources("grafana", throwingCurrent("boom"), okLatest("2.0.0")));
        lock.willAcquire(true);

        ScrapeStatus status = sut.targetedScrape(List.of(
                new ScrapeTarget("argo-cd", Side.BOTH),
                new ScrapeTarget("grafana", Side.BOTH)));

        TargetResult failed = onlyResult(status.targetResults(), "grafana");
        assertFalse(failed.succeeded());

        TargetResult ok = onlyResult(status.targetResults(), "argo-cd");
        assertTrue(ok.succeeded());
        assertEquals(1, onlyAppsNamed(store.lastWrittenApps, "argo-cd").size(),
                "the isolated failure must not prevent the healthy target from landing in the write");
    }

    // --- helpers ------------------------------------------------------------------------------

    private ScrapeSnapshot snapshotOf(VersionApplication... apps) {
        return new ScrapeSnapshot(List.of(apps), START.minus(Duration.ofMinutes(5)));
    }

    private ApplicationSources appSources(String name, String current, String latest) {
        return new ApplicationSources(name, okCurrent(current), okLatest(latest));
    }

    private CurrentVersionSource okCurrent(String value) {
        return () -> new SemverVersion(value);
    }

    private LatestVersionSource okLatest(String value) {
        return () -> new SemverVersion(value);
    }

    private CurrentVersionSource throwingCurrent(String message) {
        return () -> {
            throw new RuntimeException(message);
        };
    }

    private VersionApplication onlyApp(List<VersionApplication> apps, String name) {
        return apps.stream()
                .filter(a -> a.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no app named '" + name + "' in written snapshot"));
    }

    private List<VersionApplication> onlyAppsNamed(List<VersionApplication> apps, String name) {
        return apps.stream().filter(a -> a.name().equals(name)).toList();
    }

    private TargetResult onlyResult(List<TargetResult> results, String name) {
        return results.stream()
                .filter(r -> r.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no TargetResult for '" + name + "'"));
    }

    /** Mirrors {@code ScrapeServiceTests.FakeVersionSources}. */
    private static final class FakeVersionSources implements VersionSources {
        private List<ApplicationSources> apps = List.of();

        void seed(ApplicationSources... apps) {
            this.apps = List.of(apps);
        }

        @Override
        public List<ApplicationSources> applicationSources() {
            return apps;
        }
    }

    /** Mirrors {@code ScrapeServiceTests.FakeScrapeStateStore}. */
    private static final class FakeScrapeStateStore implements ScrapeStateStore {
        private ScrapeSnapshot snapshot;

        int writeCount;
        List<VersionApplication> lastWrittenApps;
        Instant lastWrittenAttemptAt;

        void seed(ScrapeSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        void seedEmpty() {
            this.snapshot = null;
        }

        @Override
        public Optional<ScrapeSnapshot> read() {
            return Optional.ofNullable(snapshot);
        }

        @Override
        public void write(List<VersionApplication> applications, Instant attemptAt) {
            writeCount++;
            lastWrittenApps = applications;
            lastWrittenAttemptAt = attemptAt;
            snapshot = new ScrapeSnapshot(applications, attemptAt);
        }
    }

    /** Mirrors {@code ScrapeServiceTests.FakeScrapeLock}. */
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

    /** Mirrors {@code ScrapeServiceTests.FakeScrapeRateLimiter}. */
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

    /** Mirrors {@code ScrapeServiceTests.MutableClock}. */
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
