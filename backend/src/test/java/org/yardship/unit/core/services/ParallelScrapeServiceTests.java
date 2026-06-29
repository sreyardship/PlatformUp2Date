package org.yardship.unit.core.services;

import org.junit.jupiter.api.Test;
import org.yardship.core.domain.primitives.ScrapeSnapshot;
import org.yardship.core.domain.primitives.Side;
import org.yardship.core.domain.primitives.TargetResult;
import org.yardship.core.domain.primitives.SemverVersion;
import org.yardship.core.domain.primitives.VersionApplication;
import org.yardship.core.ports.in.Outcome;
import org.yardship.core.ports.in.ScrapeStatus;
import org.yardship.core.ports.out.ApplicationSources;
import org.yardship.core.ports.out.ScrapeLock;
import org.yardship.core.ports.out.ScrapeRateLimiter;
import org.yardship.core.ports.out.ScrapeStateStore;
import org.yardship.core.ports.out.VersionSources;
import org.yardship.core.services.ApplicationVersionService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the bounded-parallel scrape contract introduced in issue 04.
 *
 * <p>Uses the 8-arg visible-for-testing constructor:
 * {@code ApplicationVersionService(VersionSources, ScrapeStateStore, ScrapeLock,
 * ScrapeRateLimiter full, ScrapeRateLimiter targeted, Duration, Clock, int concurrencyCap)}.
 *
 * <p>Assumed config property: {@code platform-config.scrape-concurrency}, default 15.
 */
class ParallelScrapeServiceTests {

    private static final Duration SCRAPE_INTERVAL = Duration.ofHours(1);
    private static final Instant START = Instant.parse("2026-06-08T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(START, ZoneOffset.UTC);

    // --- Test 1: Output follows CONFIG ORDER regardless of parallel completion order ----------

    /**
     * Seeds three apps where, under parallelism, the last-configured app (gamma) would complete
     * first (no delay), and the first-configured app (alpha) would complete last (longest sleep).
     * Output — both {@code applications} written to the store and {@code targetResults} returned in
     * the status — must always be in config order: [alpha, beta, gamma], not completion order
     * [gamma, beta, alpha].
     *
     * <p>On the current sequential implementation this passes trivially (sequential = config order).
     * The value of this test is locking ordering so the parallel implementation cannot regress it.
     */
    @Test
    void scrape_outputFollowsConfigOrder_evenWhenLaterAppsCompleteFirst() {
        // Config order: alpha (slow → last to complete in parallel),
        //               beta  (medium → second to complete),
        //               gamma (instant → first to complete).
        FakeVersionSources sources = new FakeVersionSources(List.of(
                new ApplicationSources("alpha",
                        () -> { sleepMs(200); return new SemverVersion("1.0.0"); },
                        () -> new SemverVersion("1.1.0")),
                new ApplicationSources("beta",
                        () -> { sleepMs(100); return new SemverVersion("2.0.0"); },
                        () -> new SemverVersion("2.1.0")),
                new ApplicationSources("gamma",
                        () -> new SemverVersion("3.0.0"),
                        () -> new SemverVersion("3.1.0"))));
        FakeScrapeStateStore store = new FakeScrapeStateStore();
        ApplicationVersionService sut = buildSut(sources, store, ApplicationVersionService.DEFAULT_SCRAPE_CONCURRENCY);

        ScrapeStatus status = sut.triggerScrape();

        assertEquals(Outcome.SCRAPED, status.outcome());
        assertEquals(3, status.appsAttempted());
        assertEquals(0, status.appsFailed());

        // applications written to store must be in config order: alpha, beta, gamma
        List<String> writtenNames = store.lastWrittenApps.stream()
                .map(VersionApplication::name).toList();
        assertEquals(List.of("alpha", "beta", "gamma"), writtenNames,
                "applications must follow config order, not completion order");

        // targetResults must also be in config order: alpha, beta, gamma
        List<String> resultNames = status.targetResults().stream()
                .map(TargetResult::name).toList();
        assertEquals(List.of("alpha", "beta", "gamma"), resultNames,
                "targetResults must follow config order, not completion order");
    }

    // --- Test 2: Concurrency is ACTUALLY PARALLEL and BOUNDED by the cap -------------------

    /**
     * With {@code cap=3} and 12 apps, measures the peak number of sources executing
     * concurrently. Each source atomically increments an in-flight counter on entry, records the
     * running maximum, sleeps briefly so overlap is forced, then decrements on exit.
     *
     * <p><b>Assert: {@code observedMax > 1}</b> — proves parallelism happened. On the current
     * sequential implementation the in-flight count never exceeds 1, so this assertion
     * <em>FAILS on the current sequential code</em> — that is intentional red-phase behaviour.
     *
     * <p><b>Assert: {@code observedMax <= cap}</b> — proves the Semaphore gate is respected and
     * the implementation is bounded, not an unbounded fan-out.
     */
    @Test
    void scrape_actuallyParallel_andBoundedByCap() {
        int cap = 3;
        int numApps = 12;

        AtomicInteger inflight = new AtomicInteger(0);
        AtomicInteger observedMax = new AtomicInteger(0);

        List<ApplicationSources> appList = new ArrayList<>();
        for (int i = 0; i < numApps; i++) {
            final String name = "app-" + String.format("%02d", i);
            appList.add(new ApplicationSources(name,
                    () -> {
                        int n = inflight.incrementAndGet();
                        observedMax.updateAndGet(prev -> Math.max(prev, n));
                        sleepMs(50); // hold slot so concurrent overlap is visible
                        inflight.decrementAndGet();
                        return new SemverVersion("1.0.0");
                    },
                    () -> new SemverVersion("2.0.0")));
        }

        FakeVersionSources sources = new FakeVersionSources(appList);
        FakeScrapeStateStore store = new FakeScrapeStateStore();
        ApplicationVersionService sut = buildSut(sources, store, cap);

        ScrapeStatus status = sut.triggerScrape();

        assertEquals(numApps, status.appsAttempted());
        assertEquals(0, status.appsFailed());
        assertEquals(numApps, store.lastWrittenApps.size());

        assertTrue(observedMax.get() > 1,
                "parallel implementation must run more than one source concurrently; "
                        + "observed max-in-flight=" + observedMax.get()
                        + " — sequential code gives 1, which fails this assertion");
        assertTrue(observedMax.get() <= cap,
                "concurrent reads must be bounded by cap=" + cap
                        + "; observed max-in-flight=" + observedMax.get());
    }

    // --- Test 3: Failure isolation, invariant, and config-order preserved under parallelism -

    /**
     * With {@code cap=3} and a mix of ok and throwing sources that complete out of order under
     * parallelism, asserts:
     * <ul>
     *   <li>No per-app failure propagates — outcome is {@code SCRAPED}, not an exception.</li>
     *   <li>{@code attempted/failed} counts are correct.</li>
     *   <li>{@code applications.size() + failed == attempted} invariant holds.</li>
     *   <li>Surviving apps in {@code applications} are written in config order, not completion
     *       order.</li>
     *   <li>{@code targetResults} carries ALL apps (ok and failing) in config order.</li>
     *   <li>Failed apps carry a non-empty reason; successful apps do not.</li>
     * </ul>
     */
    @Test
    void scrape_failureIsolation_andConfigOrder_holdUnderParallelOutOfOrderCompletion() {
        // Config order: app-00 (ok, slow), app-01 (throws, instant), app-02 (ok, medium),
        //               app-03 (throws, slow), app-04 (ok, instant).
        // Approximate parallel completion order: app-01, app-04, app-02, app-00, app-03.
        FakeVersionSources sources = new FakeVersionSources(List.of(
                new ApplicationSources("app-00",
                        () -> { sleepMs(150); return new SemverVersion("1.0.0"); },
                        () -> new SemverVersion("2.0.0")),
                new ApplicationSources("app-01",
                        () -> { throw new RuntimeException("source down"); },
                        () -> new SemverVersion("2.0.0")),
                new ApplicationSources("app-02",
                        () -> { sleepMs(80); return new SemverVersion("1.0.0"); },
                        () -> new SemverVersion("2.0.0")),
                new ApplicationSources("app-03",
                        () -> { sleepMs(200); throw new RuntimeException("timeout"); },
                        () -> new SemverVersion("2.0.0")),
                new ApplicationSources("app-04",
                        () -> new SemverVersion("1.0.0"),
                        () -> new SemverVersion("2.0.0"))));
        FakeScrapeStateStore store = new FakeScrapeStateStore();
        ApplicationVersionService sut = buildSut(sources, store, 3);

        ScrapeStatus status = sut.triggerScrape();

        // No per-app failure must propagate — outcome is still SCRAPED
        assertEquals(Outcome.SCRAPED, status.outcome(),
                "a per-app failure must be isolated and must not abort the scrape");

        // Count invariant: 5 attempted, 2 failed, 3 succeeded
        assertEquals(5, status.appsAttempted());
        assertEquals(2, status.appsFailed());
        assertEquals(3, status.appsSucceeded());
        assertEquals(
                status.appsAttempted(),
                store.lastWrittenApps.size() + status.appsFailed(),
                "ScrapeResult invariant: applications.size() + failed == attempted");

        // Survivors written to store must be in config order: app-00, app-02, app-04
        List<String> writtenNames = store.lastWrittenApps.stream()
                .map(VersionApplication::name).toList();
        assertEquals(List.of("app-00", "app-02", "app-04"), writtenNames,
                "surviving applications must follow config order, not completion order");

        // targetResults must carry ALL 5 apps in config order
        assertEquals(5, status.targetResults().size(),
                "one TargetResult per configured app");
        List<String> resultNames = status.targetResults().stream()
                .map(TargetResult::name).toList();
        assertEquals(List.of("app-00", "app-01", "app-02", "app-03", "app-04"), resultNames,
                "targetResults must follow config order");

        // Failures reported for the throwing sources
        TargetResult r01 = findResult(status, "app-01");
        assertFalse(r01.succeeded(), "app-01 threw — must be reported as failed");
        assertFalse(r01.reason().isEmpty(), "failed TargetResult must carry a non-empty reason");

        TargetResult r03 = findResult(status, "app-03");
        assertFalse(r03.succeeded(), "app-03 threw — must be reported as failed");
        assertFalse(r03.reason().isEmpty(), "failed TargetResult must carry a non-empty reason");

        // Survivors reported as succeeded with empty reason
        assertTrue(findResult(status, "app-00").succeeded());
        assertTrue(findResult(status, "app-02").succeeded());
        assertTrue(findResult(status, "app-04").succeeded());
    }

    // --- Helpers -----------------------------------------------------------------------------

    /**
     * Builds the service under test wired with an always-acquiring lock and an always-allowing
     * rate limiter, so {@link ApplicationVersionService#triggerScrape()} proceeds straight to the
     * scrape loop.
     */
    private static ApplicationVersionService buildSut(
            FakeVersionSources sources, FakeScrapeStateStore store, int cap) {
        AlwaysAcquireLock lock = new AlwaysAcquireLock();
        AlwaysAllowRateLimiter limiter = new AlwaysAllowRateLimiter();
        return new ApplicationVersionService(
                sources, store, lock, limiter, limiter, SCRAPE_INTERVAL, FIXED_CLOCK, cap);
    }

    private static TargetResult findResult(ScrapeStatus status, String name) {
        return status.targetResults().stream()
                .filter(r -> r.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no TargetResult named '" + name + "' in " + status.targetResults()));
    }

    private static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while sleeping in test source", e);
        }
    }

    // --- Fakes -------------------------------------------------------------------------------

    private static final class FakeVersionSources implements VersionSources {
        private final List<ApplicationSources> apps;

        FakeVersionSources(List<ApplicationSources> apps) {
            this.apps = List.copyOf(apps);
        }

        @Override
        public List<ApplicationSources> applicationSources() {
            return apps;
        }
    }

    private static final class FakeScrapeStateStore implements ScrapeStateStore {
        List<VersionApplication> lastWrittenApps = List.of();
        int writeCount;

        @Override
        public Optional<ScrapeSnapshot> read() {
            return Optional.empty(); // always stale → forces a scrape
        }

        @Override
        public void write(List<VersionApplication> applications, Instant attemptAt) {
            writeCount++;
            lastWrittenApps = List.copyOf(applications);
        }
    }

    private static final class AlwaysAcquireLock implements ScrapeLock {
        @Override
        public boolean tryAcquire() {
            return true;
        }

        @Override
        public void release() {
        }
    }

    private static final class AlwaysAllowRateLimiter implements ScrapeRateLimiter {
        @Override
        public Decision tryAcquire(Instant now) {
            return Decision.allowed(Integer.MAX_VALUE, 0);
        }

        @Override
        public Budget peek(Instant now) {
            return new Budget(Integer.MAX_VALUE, 0);
        }
    }
}
