package org.yardship.performance;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yardship.core.domain.primitives.VersionApplication;
import org.yardship.core.ports.out.VersionSources;
import org.yardship.core.services.ApplicationVersionService;
import org.yardship.performance.fakes.AlwaysAllowScrapeRateLimiter;
import org.yardship.performance.fakes.AlwaysGrantScrapeLock;
import org.yardship.performance.fakes.InMemoryScrapeStateStore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Performance harness for end-to-end scrape timing.
 *
 * <p>This harness is tagged {@code "performance"} and is intentionally EXCLUDED from the default
 * {@code gradle test} task. Run it explicitly with {@code gradle perfTest}.
 *
 * <p>Setup: WireMock registers {@link ScrapePerfWireMockResource#MAX_N} apps up front, each with
 * http {@code current} + github-release {@code latest} stubs returning after a uniform
 * {@value ScrapePerfWireMockResource#STUB_DELAY_MS} ms fixed delay. The real {@link VersionSources}
 * bean (injected by the CDI container, sized to {@code MAX_N}) is sub-listed per measurement point
 * so no per-N WireMock restart is required. The three ports —
 * {@link org.yardship.core.ports.out.ScrapeStateStore},
 * {@link org.yardship.core.ports.out.ScrapeLock}, and
 * {@link org.yardship.core.ports.out.ScrapeRateLimiter} — are satisfied by simple in-memory fakes
 * that involve no Valkey and impose no budget.
 *
 * <p>Contains three test methods:
 * <ul>
 *   <li>{@link #baseline_oneScrape_printsElapsedMs_andReturnsExpectedAppCount()} — the original
 *       slice-01 tracer bullet: drives ONE scrape over the first
 *       {@link ScrapePerfWireMockResource#APP_COUNT} apps and prints the raw wall-clock.</li>
 *   <li>{@link #warmupAndIterations_printsMedianMinMax()} — the slice-02 stable-statistic harness:
 *       runs {@link #WARMUP_COUNT} warmup scrapes (timings discarded), then {@link #ITERATION_COUNT}
 *       timed iterations over the first {@link ScrapePerfWireMockResource#APP_COUNT} apps, and
 *       reduces wall-clocks to median + min + max.</li>
 *   <li>{@link #sweep_printsPerNBaselineTable()} — the slice-03 sweep: for each N in {@link #SWEEP}
 *       it measures a sub-list of the first N apps and prints an aligned per-N table showing
 *       median/min/max. The sequential wall-clock should grow roughly linearly with N.</li>
 * </ul>
 *
 * <p>No timing assertion is made — these are baseline measurements, not bounds.
 */
@QuarkusTest
@Tag("performance")
// restrictToAnnotatedClass: a QuarkusTestResource is GLOBAL by default — it would apply to every
// @QuarkusTest in the module and pollute their platform-config (e.g. overriding apps[0].name),
// even though this harness is tag-excluded from `gradle test`. Restricting it keeps the WireMock
// app config scoped to the harness alone.
@QuarkusTestResource(value = ScrapePerfWireMockResource.class, restrictToAnnotatedClass = true)
class ScrapeBaselineHarness {

    // Fixed clock: staleness check doesn't matter here (the store starts empty on each iteration),
    // but the clock is required by the ApplicationVersionService constructor.
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-06-29T00:00:00Z"), ZoneOffset.UTC);

    // Use a long scrape interval: the in-memory store is reset before each iteration, so the
    // service always sees an absent snapshot and scrapes unconditionally — the interval value
    // is never actually consulted by the staleness check.
    private static final Duration SCRAPE_INTERVAL = Duration.ofHours(1);

    // Slice-02 constants: warmup discards JIT / connection-pool variance;
    // iterations produce the stable statistic.
    private static final int WARMUP_COUNT = 1;
    private static final int ITERATION_COUNT = 7;

    /**
     * Slice-03 sweep: N values to measure in order.
     * {@link ScrapePerfWireMockResource#MAX_N} must be ≥ max(SWEEP).
     * Sequential wall-clock should grow roughly as 2 * N * {@link ScrapePerfWireMockResource#STUB_DELAY_MS} ms.
     */
    private static final int[] SWEEP = {1, 5, 10, 25, 50};

    @Inject
    VersionSources versionSources;

    @Test
    void baseline_oneScrape_printsElapsedMs_andReturnsExpectedAppCount() {
        InMemoryScrapeStateStore stateStore = new InMemoryScrapeStateStore();
        VersionSources sources = firstN(ScrapePerfWireMockResource.APP_COUNT);
        ApplicationVersionService service = newService(stateStore, sources);

        long startNs = System.nanoTime();
        List<VersionApplication> result = service.getApplications();
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

        System.out.printf("[perf] baseline single scrape elapsed: %d ms (n=%d apps, delay=%d ms/stub)%n",
                elapsedMs, result.size(), ScrapePerfWireMockResource.STUB_DELAY_MS);

        // Self-guard: the result must be non-empty and match the expected app count.
        // This is NOT a timing assertion — it just proves the scrape ran and produced data.
        assertFalse(result.isEmpty(), "scrape must return at least one application");
        assertEquals(ScrapePerfWireMockResource.APP_COUNT, result.size(),
                "scrape must return exactly the configured number of apps");
    }

    /**
     * Slice-02: warmup + repeated iterations → stable statistic.
     *
     * <p>Runs {@link #WARMUP_COUNT} warmup scrape(s) whose timings are discarded (absorbs JIT and
     * connection-pool startup). Then runs {@link #ITERATION_COUNT} timed iterations, each starting
     * from an empty snapshot (the store is {@link InMemoryScrapeStateStore#reset() reset} before
     * each iteration so the service is forced to re-run the full scrape loop rather than serving
     * a cached result). Reduces wall-clocks to median, min, and max and prints one summary line.
     *
     * <p>Median convention: for even {@link #ITERATION_COUNT}, the two middle values are averaged.
     * For odd counts, the single middle element is used directly.
     *
     * <p>No timing assertion is made. Self-guard: each iteration must return the expected app count,
     * proving the scrape loop genuinely ran (not a cache hit).
     */
    @Test
    void warmupAndIterations_printsMedianMinMax() {
        int n = ScrapePerfWireMockResource.APP_COUNT;
        Stats stats = measure(firstN(n), n);

        // Label apps/iters explicitly: 'apps' is the configured app count (the quantity slice 03
        // sweeps as N), 'iters' is how many timed runs were reduced into this statistic.
        System.out.printf(
                "[perf] apps=%d iters=%d delay=%dms → median=%dms min=%dms max=%dms%n",
                n,
                ITERATION_COUNT,
                ScrapePerfWireMockResource.STUB_DELAY_MS,
                stats.median,
                stats.min,
                stats.max);
    }

    /**
     * Slice-03: app-count sweep → baseline table.
     *
     * <p>For each N in {@link #SWEEP}, builds a first-N sub-list wrapper over the injected
     * {@link VersionSources} (which is sized to {@link ScrapePerfWireMockResource#MAX_N}),
     * measures it with the same warmup + iterations logic as slice 02, and records the row.
     * After the sweep, prints ONE aligned table with columns N | median(ms) | min(ms) | max(ms).
     *
     * <p>The sequential baseline should show wall-clock growing roughly linearly with N
     * (≈ 2 * N * {@link ScrapePerfWireMockResource#STUB_DELAY_MS} ms) — that linear column is
     * the baseline against which slice 04's parallel version will be judged.
     *
     * <p>No timing assertion is made. Self-guard: each sweep point's sub-list must return
     * exactly N apps.
     */
    @Test
    void sweep_printsPerNBaselineTable() {
        // Guard the MAX_N >= max(SWEEP) invariant loudly: a retune of SWEEP above the registered
        // app count would otherwise surface as a raw IndexOutOfBoundsException from subList deep in
        // a measurement loop.
        for (int n : SWEEP) {
            assertFalse(n > ScrapePerfWireMockResource.MAX_N,
                    "SWEEP value " + n + " exceeds ScrapePerfWireMockResource.MAX_N ("
                            + ScrapePerfWireMockResource.MAX_N + "); raise MAX_N to sweep that many apps");
        }

        // Collect rows first, then print one aligned table.
        long[][] rows = new long[SWEEP.length][3]; // [i][0]=median, [i][1]=min, [i][2]=max

        for (int i = 0; i < SWEEP.length; i++) {
            int n = SWEEP[i];
            Stats stats = measure(firstN(n), n);
            rows[i][0] = stats.median;
            rows[i][1] = stats.min;
            rows[i][2] = stats.max;
        }

        // Print the aligned table.
        System.out.printf("%n[perf] scrape sweep (delay=%dms, warmup=%d, iterations=%d):%n",
                ScrapePerfWireMockResource.STUB_DELAY_MS, WARMUP_COUNT, ITERATION_COUNT);
        System.out.printf("%-4s | %11s | %8s | %8s%n", "N", "median(ms)", "min(ms)", "max(ms)");
        System.out.printf("%-4s-+-%11s-+-%8s-+-%8s%n", "----", "-----------", "--------", "--------");
        for (int i = 0; i < SWEEP.length; i++) {
            System.out.printf("%-4d | %11d | %8d | %8d%n",
                    SWEEP[i], rows[i][0], rows[i][1], rows[i][2]);
        }
        System.out.println();
    }

    // -------------------------------------------------------------------------
    // Shared infrastructure
    // -------------------------------------------------------------------------

    /**
     * Returns a {@link VersionSources} that exposes only the first {@code n} apps from the
     * injected bean. Used to sub-list the {@link ScrapePerfWireMockResource#MAX_N}-sized CDI bean
     * down to the desired sweep point without restarting WireMock.
     */
    private VersionSources firstN(int n) {
        return () -> versionSources.applicationSources().subList(0, n);
    }

    /**
     * Builds an {@link ApplicationVersionService} over the given {@link VersionSources} plus the
     * in-memory port fakes and the fixed clock.
     */
    private ApplicationVersionService newService(InMemoryScrapeStateStore stateStore, VersionSources sources) {
        return new ApplicationVersionService(
                sources,
                stateStore,
                new AlwaysGrantScrapeLock(),
                new AlwaysAllowScrapeRateLimiter(),
                new AlwaysAllowScrapeRateLimiter(),
                SCRAPE_INTERVAL,
                FIXED_CLOCK);
    }

    /**
     * Runs {@link #WARMUP_COUNT} warmup scrapes (discarded) then {@link #ITERATION_COUNT} timed
     * iterations over the given sources, returning the median/min/max wall-clock in milliseconds.
     *
     * <p>Each iteration resets the in-memory store so the service is forced to re-run the full
     * scrape loop rather than serving a cached result.
     *
     * <p>Self-guard: every scrape (warmup and timed) must return exactly {@code expectedAppCount}
     * applications, proving the loop genuinely ran.
     *
     * @param sources          the {@link VersionSources} to measure (typically a firstN sub-list)
     * @param expectedAppCount the number of apps the sub-list exposes; asserted on every scrape
     * @return a {@link Stats} record with median, min, and max wall-clock in milliseconds
     */
    private Stats measure(VersionSources sources, int expectedAppCount) {
        InMemoryScrapeStateStore stateStore = new InMemoryScrapeStateStore();
        ApplicationVersionService service = newService(stateStore, sources);

        // Warmup: run WARMUP_COUNT scrapes and discard their timings.
        for (int w = 0; w < WARMUP_COUNT; w++) {
            stateStore.reset();
            List<VersionApplication> warmupResult = service.getApplications();
            assertEquals(expectedAppCount, warmupResult.size(),
                    "warmup iteration " + w + " must return exactly " + expectedAppCount + " apps");
        }

        // Timed iterations: reset the store before each so the scrape loop re-runs.
        List<Long> elapsedMsList = new ArrayList<>(ITERATION_COUNT);
        for (int i = 0; i < ITERATION_COUNT; i++) {
            stateStore.reset();
            long startNs = System.nanoTime();
            List<VersionApplication> result = service.getApplications();
            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
            elapsedMsList.add(elapsedMs);

            assertEquals(expectedAppCount, result.size(),
                    "timed iteration " + i + " must return exactly " + expectedAppCount + " apps");
        }

        // Compute median, min, max.
        List<Long> sorted = new ArrayList<>(elapsedMsList);
        Collections.sort(sorted);

        long min = sorted.get(0);
        long max = sorted.get(sorted.size() - 1);
        long median;
        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            // Odd count: single middle element.
            median = sorted.get(mid);
        } else {
            // Even count: average of the two middle elements.
            median = (sorted.get(mid - 1) + sorted.get(mid)) / 2;
        }

        return new Stats(median, min, max);
    }

    /** Holds the median, min, and max wall-clock measurements (all in milliseconds). */
    private record Stats(long median, long min, long max) {}
}
