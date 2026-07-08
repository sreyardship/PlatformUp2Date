package org.yardship.unit.core.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yardship.core.ports.in.Outcome;
import org.yardship.core.domain.primitives.ScrapeSnapshot;
import org.yardship.core.domain.primitives.Side;
import org.yardship.core.domain.primitives.SideObservation;
import org.yardship.core.domain.primitives.TargetResult;
import org.yardship.core.ports.in.ScrapeStatus;
import org.yardship.core.domain.primitives.SemverVersion;
import org.yardship.core.domain.primitives.VersionValue;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void triggerScrape_lockWon_scrapesWritesAndReturnsScrapedWithCountsFromTheLoop() {
        // Three apps; one latest source throws and has no prior → issue 03: beta is persisted as
        // Unresolved (not dropped). attempted=3, failed=1 (beta is Unresolved), apps.size()=3.
        sources.seed(
                appSources("alpha", "1.0.0", "2.0.0"),
                new ApplicationSources("beta", okCurrent("1.0.0"), throwingLatest("github down")),
                appSources("gamma", "3.0.0", "4.0.0"));
        lock.willAcquire(true);

        ScrapeStatus status = sut.triggerScrape();

        assertEquals(Outcome.SCRAPED, status.outcome());
        assertEquals(3, status.appsAttempted());
        assertEquals(1, status.appsFailed(), "a side-failed app (no prior) still counts as failed even though persisted");
        assertEquals(2, status.appsSucceeded(), "succeeded == attempted - failed");

        // Issue 03: beta is now PERSISTED as Unresolved rather than dropped.
        assertEquals(3, store.lastWrittenApps.size(),
                "issue 03: all apps including Unresolved ones must be persisted (not dropped)");
        assertTrue(store.lastWrittenApps.stream().anyMatch(a -> a.name().equals("beta")),
                "beta must appear in the persisted snapshot even though its latest source threw");
        VersionApplication persistedBeta = store.lastWrittenApps.stream()
                .filter(a -> a.name().equals("beta")).findFirst().orElseThrow();
        assertFalse(persistedBeta.latest().isResolved(),
                "beta must be persisted as Unresolved (latest side has no value)");
        assertEquals(1, store.writeCount, "the winner must write a fresh snapshot");
        assertEquals(1, lock.acquireCount, "trigger must acquire the lock exactly once");
        assertEquals(1, lock.releaseCount, "the winner must release the lock");
    }

    @Test
    void triggerScrape_scrapeResultInvariant_allAppsPersistedRegardlessOfFailures() {
        // Issue 03 changes the invariant: ALL attempted apps land in the snapshot (including
        // Unresolved ones). The OLD invariant `apps.size() + failed == attempted` no longer holds
        // because apps are never dropped. The NEW invariant is `apps.size() == attempted`.
        sources.seed(
                appSources("a", "1.0.0", "2.0.0"),
                new ApplicationSources("b", throwingCurrent("503"), okLatest("2.0.0")),
                new ApplicationSources("c", okCurrent("1.0.0"), throwingLatest("down")),
                appSources("d", "1.0.0", "2.0.0"));
        lock.willAcquire(true);

        ScrapeStatus status = sut.triggerScrape();

        assertEquals(4, status.appsAttempted());
        assertEquals(2, status.appsFailed(), "b and c each have a side failure → counted as failed");
        // Issue 03 new invariant: all apps are persisted (including Unresolved b and c).
        assertEquals(4, store.lastWrittenApps.size(),
                "new ScrapeResult invariant (issue 03): applications.size() == attempted; all apps persisted");
        // The old invariant apps.size() + failed == attempted (4 + 2 == 4) no longer holds.
        // Instead: apps.size() == attempted (4 == 4) and failed == count of Unresolved apps.
        // b: current threw (no prior), so current side has no value.
        // c: latest threw (no prior), so latest side has no value.
        VersionApplication b = store.lastWrittenApps.stream().filter(a -> a.name().equals("b")).findFirst().orElseThrow();
        VersionApplication c = store.lastWrittenApps.stream().filter(a -> a.name().equals("c")).findFirst().orElseThrow();
        assertFalse(b.current().isResolved(), "b's current side (source threw, no prior) must have no value");
        assertFalse(c.latest().isResolved(), "c's latest side (source threw, no prior) must have no value");
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
        // A per-app source failure is isolated inside the loop: triggerScrape returns SCRAPED (with
        // failed counted) — it does NOT throw. The lock is still released. Issue 03: the failed app
        // is persisted as Unresolved rather than dropped.
        sources.seed(
                new ApplicationSources("alpha", okCurrent("1.0.0"), throwingLatest("boom")),
                appSources("beta", "1.0.0", "2.0.0"));
        lock.willAcquire(true);

        ScrapeStatus status = sut.triggerScrape();

        assertEquals(Outcome.SCRAPED, status.outcome());
        assertEquals(2, status.appsAttempted());
        assertEquals(1, status.appsFailed(), "alpha has a failing latest side → counted as failed");
        assertEquals(1, lock.releaseCount, "an isolated per-app failure must not leak the lock");
        // Issue 03: alpha is persisted as Unresolved, not dropped.
        assertEquals(2, store.lastWrittenApps.size(),
                "issue 03: alpha must be persisted as Unresolved (not dropped from the snapshot)");
    }

    // --- Issue 03: Unresolved app persistence ------------------------------------------------
    //
    // When a side's source throws and there is NO prior value for that side, the app must still
    // be persisted (as Unresolved) rather than dropped. This replaces the previous "re-throw and
    // drop" behaviour with "persist-as-Unresolved".

    @Test
    void triggerScrape_firstScrape_bothSourcesThrow_persistsFullyPendingApp_noPrior() {
        // First scrape ever (no prior): BOTH sides throw. Previously the app was dropped; after
        // issue 03 it must be persisted as Unresolved (both sides pending/value-less).
        sources.seed(
                new ApplicationSources("cold-app", throwingCurrent("endpoint offline"), throwingLatest("github down")),
                appSources("ok-app", "1.0.0", "2.0.0"));
        lock.willAcquire(true);

        ScrapeStatus status = sut.triggerScrape();

        assertEquals(Outcome.SCRAPED, status.outcome());
        assertEquals(2, status.appsAttempted());
        // cold-app persisted as Unresolved, not dropped → apps.size() == attempted
        assertEquals(2, store.lastWrittenApps.size(),
                "a first-scrape app with both sides failing must be persisted as Unresolved, not dropped");
        VersionApplication coldApp = store.lastWrittenApps.stream()
                .filter(a -> a.name().equals("cold-app")).findFirst()
                .orElseThrow(() -> new AssertionError("cold-app must be in the persisted snapshot"));
        assertFalse(coldApp.current().isResolved() && coldApp.latest().isResolved(),
                "a first-scrape app with throwing sources must be Unresolved (at least one side has no value)");
        assertTrue(coldApp.current().value().isEmpty(),
                "current side must have no value (source threw, no prior)");
        assertTrue(coldApp.latest().value().isEmpty(),
                "latest side must have no value (source threw, no prior)");
    }

    @Test
    void triggerScrape_firstScrape_oneSideThrows_persistsPartiallyUnresolvedApp() {
        // First scrape: current resolves, latest throws with no prior. The app must be persisted
        // with current resolved and latest Unresolved (pending or failed-never-succeeded).
        Instant priorNone = null; // no prior for latest
        sources.seed(
                new ApplicationSources("cold-app", okCurrent("1.0.0"), throwingLatest("github down")));
        lock.willAcquire(true);

        sut.triggerScrape();

        VersionApplication app = store.lastWrittenApps.stream()
                .filter(a -> a.name().equals("cold-app")).findFirst()
                .orElseThrow(() -> new AssertionError("cold-app must be persisted"));
        assertFalse(app.latest().isResolved(), "the latest side must have no value (source threw, no prior)");
        assertEquals("1.0.0", app.current().value().orElseThrow().value(),
                "current side must carry the resolved value");
        assertTrue(app.latest().value().isEmpty(),
                "latest side must have no value (source threw, no prior)");
        assertTrue(app.latest().lastFailureAt().isPresent(),
                "latest side must carry a lastFailureAt stamp (it attempted and failed)");
        assertEquals(START, app.latest().lastFailureAt().orElseThrow(),
                "lastFailureAt must equal clock.instant() at scrape time");
    }

    @Test
    void triggerScrape_firstScrape_oneSideThrows_isCountedAsFailed_butStillPersisted() {
        // An Unresolved app (side threw with no prior) is counted in appsFailed (partial failure)
        // AND is persisted in the snapshot. The new invariant: apps.size() == attempted.
        sources.seed(
                new ApplicationSources("cold-app", okCurrent("1.0.0"), throwingLatest("down")),
                appSources("good-app", "2.0.0", "3.0.0"));
        lock.willAcquire(true);

        ScrapeStatus status = sut.triggerScrape();

        assertEquals(2, status.appsAttempted());
        assertEquals(1, status.appsFailed(),
                "cold-app has a failing side → counts in appsFailed");
        assertEquals(1, status.appsSucceeded(),
                "only good-app is fully Resolved → counts as succeeded");
        // New invariant: ALL apps are persisted.
        assertEquals(2, store.lastWrittenApps.size(),
                "new invariant (issue 03): all attempted apps are persisted including Unresolved ones");
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

    // --- Per-app target results (issue 02) -----------------------------------------------------
    //
    // The full scrape now reports WHICH apps failed and why, reusing TargetResult from issue 01.
    // The loop reads both sides of each app in one try, so every full-scrape TargetResult carries
    // side == BOTH (app-level granularity; see docs/adr/0006). No behaviour change to the scrape
    // loop itself — only the telemetry gains identity.

    @Test
    void triggerScrape_lockWon_targetResults_nameEachAppWithSucceededAndReason() {
        sources.seed(
                appSources("alpha", "1.0.0", "2.0.0"),
                new ApplicationSources("beta", okCurrent("1.0.0"), throwingLatest("github down")),
                appSources("gamma", "3.0.0", "4.0.0"));
        lock.willAcquire(true);

        ScrapeStatus status = sut.triggerScrape();

        assertEquals(3, status.targetResults().size(), "one TargetResult per configured app");

        TargetResult alpha = findResult(status, "alpha");
        assertTrue(alpha.succeeded(), "alpha's sources did not throw");
        assertEquals("", alpha.reason(), "reason is empty on success");

        TargetResult beta = findResult(status, "beta");
        assertFalse(beta.succeeded(), "beta's latest source threw");
        assertFalse(beta.reason().isEmpty(), "a failed target must carry a non-empty reason");

        TargetResult gamma = findResult(status, "gamma");
        assertTrue(gamma.succeeded(), "gamma's sources did not throw");
    }

    @Test
    void triggerScrape_targetResults_everyEntryReportsSideBoth() {
        sources.seed(
                appSources("alpha", "1.0.0", "2.0.0"),
                new ApplicationSources("beta", okCurrent("1.0.0"), throwingLatest("down")));
        lock.willAcquire(true);

        ScrapeStatus status = sut.triggerScrape();

        for (TargetResult result : status.targetResults()) {
            assertEquals(Side.BOTH, result.side(),
                    "a full scrape reads both sides of an app in one try: " + result.name());
        }
    }

    @Test
    void triggerScrape_targetResults_countsAgreeWithAppsAttemptedSucceededFailed() {
        sources.seed(
                appSources("a", "1.0.0", "2.0.0"),
                new ApplicationSources("b", throwingCurrent("503"), okLatest("2.0.0")),
                new ApplicationSources("c", okCurrent("1.0.0"), throwingLatest("down")),
                appSources("d", "1.0.0", "2.0.0"));
        lock.willAcquire(true);

        ScrapeStatus status = sut.triggerScrape();

        assertEquals(status.appsAttempted(), status.targetResults().size(),
                "targetResults size must equal appsAttempted");
        long succeededInList = status.targetResults().stream().filter(TargetResult::succeeded).count();
        assertEquals(status.appsSucceeded(), succeededInList,
                "succeeded entries in targetResults must equal appsSucceeded");
        long failedInList = status.targetResults().stream().filter(r -> !r.succeeded()).count();
        assertEquals(status.appsFailed(), failedInList,
                "failed entries in targetResults must equal appsFailed");
    }

    @Test
    void triggerScrape_noConfiguredApps_targetResultsIsEmpty() {
        sources.seedEmpty();
        lock.willAcquire(true);

        ScrapeStatus status = sut.triggerScrape();

        assertTrue(status.targetResults().isEmpty(), "no configured apps means no target results");
    }

    private TargetResult findResult(ScrapeStatus status, String name) {
        return status.targetResults().stream()
                .filter(r -> r.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no TargetResult named '" + name + "' in " + status.targetResults()));
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
        assertEquals(0, targetedRateLimiter.tryAcquireCount,
                "triggerScrape (full path) must never consult the targeted budget");
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
        assertEquals(0, targetedRateLimiter.tryAcquireCount,
                "triggerScrape (full path) must never consult the targeted budget");
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
        assertEquals(0, targetedRateLimiter.tryAcquireCount,
                "triggerScrape (full path) must never consult the targeted budget");
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

    // --- Scrape stamps lastSuccessAt from the clock (slice 01) --------------------------------
    //
    // Each side of every successfully scraped app must carry lastSuccessAt == clock.instant()
    // at the moment the scrape executes. lastFailureAt must be absent for successful reads.
    // This covers acceptance criterion: "A successful scrape stamps each side's last-success
    // time from the injected Clock."

    @Test
    void triggerScrape_stampsLastSuccessAt_fromClock_onBothSides() {
        sources.seed(appSources("my-app", "1.0.0", "2.0.0"));
        lock.willAcquire(true);

        sut.triggerScrape();

        VersionApplication written = store.lastWrittenApps.getFirst();
        assertEquals(START, written.current().lastSuccessAt().orElseThrow(),
                "current side's lastSuccessAt must be clock.instant() at scrape time");
        assertEquals(START, written.latest().lastSuccessAt().orElseThrow(),
                "latest side's lastSuccessAt must be clock.instant() at scrape time");
        assertTrue(written.current().lastFailureAt().isEmpty(),
                "a successful current read must not set lastFailureAt (slice 01)");
        assertTrue(written.latest().lastFailureAt().isEmpty(),
                "a successful latest read must not set lastFailureAt (slice 01)");
    }

    @Test
    void triggerScrape_stampUsesClockInstantAtScrapeTime_notAtObjectConstruction() {
        // Verify that the stamp comes from the injected clock at the moment the scrape runs,
        // not from some static initialiser or construction time.
        sources.seed(appSources("my-app", "1.0.0", "2.0.0"));
        lock.willAcquire(true);
        // Advance the clock before scraping; the stamp must reflect the advanced instant.
        clock.advance(Duration.ofHours(3));
        Instant expectedStamp = START.plus(Duration.ofHours(3));

        sut.triggerScrape();

        VersionApplication written = store.lastWrittenApps.getFirst();
        assertEquals(expectedStamp, written.current().lastSuccessAt().orElseThrow(),
                "lastSuccessAt must be the clock instant at the moment the scrape ran");
        assertEquals(expectedStamp, written.latest().lastSuccessAt().orElseThrow());
    }

    // --- Slice 02: full-scrape merge-over-prior with per-side failure stamps ------------------
    //
    // Issue 02 changes the full-scrape path from "write fresh / drop failed apps" to per-side
    // resolve + merge-over-prior + failure-stamp (same model as the targeted-scrape path).
    // Specifically:
    //   - When one side's source throws the side keeps its prior value + lastSuccessAt and gains
    //     lastFailureAt == clock.instant().
    //   - The other side (whose source succeeded) updates normally (new value + lastSuccessAt,
    //     lastFailureAt absent).
    //   - The failed app is STILL written to the snapshot (not dropped); it carries a valid
    //     SideObservation with value + lastSuccessAt from the prior snapshot and a new lastFailureAt.
    //   - Fleet lastAttemptAt still advances (full scrape behaviour, unchanged).

    @Test
    void triggerScrape_overPriorSnapshot_oneSourceThrows_failedSideKeepsPriorValueAndLastSuccessAt() {
        // Seed a prior snapshot for "alpha" with a known lastSuccessAt.
        Instant priorSuccessAt = START.minusSeconds(3600);
        VersionApplication priorAlpha = new VersionApplication("alpha",
                SideObservation.resolved(new SemverVersion("1.0.0"), priorSuccessAt),
                SideObservation.resolved(new SemverVersion("2.0.0"), priorSuccessAt));
        store.seed(new ScrapeSnapshot(List.of(priorAlpha), priorSuccessAt));

        // alpha's latest source will throw; current source returns a value.
        sources.seed(new ApplicationSources("alpha",
                okCurrent("1.1.0"),
                throwingLatest("github down")));
        lock.willAcquire(true);

        sut.triggerScrape();

        // alpha must still be in the written snapshot (not dropped).
        VersionApplication written = store.lastWrittenApps.stream()
                .filter(a -> a.name().equals("alpha"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("alpha must remain in the snapshot after a partial failure"));

        // The latest side (which threw) must keep the prior value.
        assertEquals("2.0.0", written.latest().value().orElseThrow().value(),
                "the failed latest side must keep the prior value");

        // The latest side must keep the prior lastSuccessAt.
        assertEquals(priorSuccessAt, written.latest().lastSuccessAt().orElseThrow(),
                "the failed latest side must keep the prior lastSuccessAt");

        // The latest side must gain a lastFailureAt == clock.instant().
        assertTrue(written.latest().lastFailureAt().isPresent(),
                "the failed latest side must have a lastFailureAt stamp");
        assertEquals(START, written.latest().lastFailureAt().orElseThrow(),
                "lastFailureAt must equal clock.instant() at scrape time");

        // The current side (which succeeded) must update normally.
        assertEquals("1.1.0", written.current().value().orElseThrow().value(),
                "the successful current side must update to the new value");
        assertEquals(START, written.current().lastSuccessAt().orElseThrow(),
                "the successful current side must get a new lastSuccessAt");
        assertTrue(written.current().lastFailureAt().isEmpty(),
                "the successful current side must not get a lastFailureAt");
    }

    @Test
    void triggerScrape_overPriorSnapshot_oneSourceThrows_otherAppUpdatesNormally() {
        // Two-app snapshot; one app partial-fails, the other must be unaffected.
        Instant priorAt = START.minusSeconds(3600);
        store.seed(new ScrapeSnapshot(List.of(
                new VersionApplication("alpha",
                        SideObservation.resolved(new SemverVersion("1.0.0"), priorAt),
                        SideObservation.resolved(new SemverVersion("2.0.0"), priorAt)),
                new VersionApplication("beta",
                        SideObservation.resolved(new SemverVersion("3.0.0"), priorAt),
                        SideObservation.resolved(new SemverVersion("4.0.0"), priorAt))), priorAt));

        sources.seed(
                new ApplicationSources("alpha", okCurrent("1.1.0"), throwingLatest("down")),
                appSources("beta", "3.1.0", "4.1.0"));
        lock.willAcquire(true);

        sut.triggerScrape();

        // beta must be updated with fresh values.
        VersionApplication beta = store.lastWrittenApps.stream()
                .filter(a -> a.name().equals("beta"))
                .findFirst()
                .orElseThrow();
        assertEquals("3.1.0", beta.current().value().orElseThrow().value());
        assertEquals("4.1.0", beta.latest().value().orElseThrow().value());
        assertEquals(START, beta.current().lastSuccessAt().orElseThrow());
        assertTrue(beta.current().lastFailureAt().isEmpty());
        assertTrue(beta.latest().lastFailureAt().isEmpty());
    }

    @Test
    void triggerScrape_overPriorSnapshot_failedSide_marksFailedRefresh() {
        // failedRefresh() on the written side must return true after a per-side failure.
        Instant priorAt = START.minusSeconds(3600);
        store.seed(new ScrapeSnapshot(List.of(
                new VersionApplication("alpha",
                        SideObservation.resolved(new SemverVersion("1.0.0"), priorAt),
                        SideObservation.resolved(new SemverVersion("2.0.0"), priorAt))), priorAt));

        sources.seed(new ApplicationSources("alpha", okCurrent("1.1.0"), throwingLatest("down")));
        lock.willAcquire(true);

        sut.triggerScrape();

        VersionApplication written = store.lastWrittenApps.getFirst();
        assertTrue(written.latest().failedRefresh(),
                "the failed latest side must report failedRefresh() == true after a per-side failure");
        assertFalse(written.current().failedRefresh(),
                "the successful current side must NOT report failedRefresh()");
    }

    @Test
    void triggerScrape_overPriorSnapshot_fleetLastAttemptAt_advances() {
        // Fleet-wide lastAttemptAt must still advance on a full scrape even in the new
        // per-side merge model (regression guard).
        Instant priorAt = START.minusSeconds(3600);
        store.seed(new ScrapeSnapshot(List.of(
                new VersionApplication("alpha",
                        SideObservation.resolved(new SemverVersion("1.0.0"), priorAt),
                        SideObservation.resolved(new SemverVersion("2.0.0"), priorAt))), priorAt));

        sources.seed(new ApplicationSources("alpha", okCurrent("1.1.0"), throwingLatest("down")));
        lock.willAcquire(true);

        sut.triggerScrape();

        assertEquals(START, store.lastWrittenAttemptAt,
                "a full scrape must advance lastAttemptAt to clock.instant()");
    }

    // --- helpers ------------------------------------------------------------------------------

    private VersionApplication createUp2DateApplication() {
        // Updated to slice 01 shape: each side is a SideObservation with a fixed stamp.
        return new VersionApplication("Another-app",
                SideObservation.resolved(new SemverVersion("2.2.2"), START),
                SideObservation.resolved(new SemverVersion("2.2.2"), START));
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
