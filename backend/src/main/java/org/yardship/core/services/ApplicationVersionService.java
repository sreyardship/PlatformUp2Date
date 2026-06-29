package org.yardship.core.services;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.yardship.core.ports.out.FullScrapeBudget;
import org.yardship.core.ports.out.TargetedScrapeBudget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yardship.core.ports.out.ApplicationSources;
import org.yardship.core.ports.out.ScrapeResult;
import org.yardship.core.domain.primitives.ScrapeSnapshot;
import org.yardship.core.domain.primitives.ScrapeTarget;
import org.yardship.core.domain.primitives.Side;
import org.yardship.core.domain.primitives.TargetResult;
import org.yardship.core.domain.primitives.VersionApplication;
import org.yardship.core.domain.primitives.VersionValue;
import org.yardship.core.ports.in.ApplicationVersionPort;
import org.yardship.core.ports.in.ScrapeStatus;
import org.yardship.core.ports.out.ScrapeLock;
import org.yardship.core.ports.out.ScrapeRateLimiter;
import org.yardship.core.ports.out.ScrapeStateStore;
import org.yardship.core.ports.out.VersionSources;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

@ApplicationScoped
public class ApplicationVersionService implements ApplicationVersionPort {

    private final Logger logger = LoggerFactory.getLogger(ApplicationVersionService.class);

    /** Default global concurrency cap for the bounded-parallel scrape loop (issue 04). */
    public static final int DEFAULT_SCRAPE_CONCURRENCY = 15;

    private final VersionSources versionSources;
    private final ScrapeStateStore scrapeStateStore;
    private final ScrapeLock scrapeLock;
    private final ScrapeRateLimiter fullScrapeRateLimiter;
    private final ScrapeRateLimiter targetedScrapeRateLimiter;
    private final Duration scrapeInterval;
    private final Clock clock;
    private final int concurrencyCap;

    @Inject
    public ApplicationVersionService(
            VersionSources versionSources,
            ScrapeStateStore scrapeStateStore,
            ScrapeLock scrapeLock,
            @FullScrapeBudget ScrapeRateLimiter fullScrapeRateLimiter,
            @TargetedScrapeBudget ScrapeRateLimiter targetedScrapeRateLimiter,
            @ConfigProperty(name = "platform-config.scrape-interval") Duration scrapeInterval,
            @ConfigProperty(name = "platform-config.scrape-concurrency", defaultValue = "15") int scrapeConcurrency) {
        this(
                versionSources,
                scrapeStateStore,
                scrapeLock,
                fullScrapeRateLimiter,
                targetedScrapeRateLimiter,
                scrapeInterval,
                Clock.systemUTC(),
                scrapeConcurrency);
    }

    // Visible for testing: lets tests drive the staleness clock deterministically, and inject the
    // full-scrape and targeted-scrape budgets explicitly (issue 03 — two distinct rolling-window
    // budgets so agent-driven targeted scrapes cannot starve the UI's full-Refresh budget).
    // Delegates to the 8-arg ctor with the default concurrency cap.
    public ApplicationVersionService(
            VersionSources versionSources,
            ScrapeStateStore scrapeStateStore,
            ScrapeLock scrapeLock,
            ScrapeRateLimiter fullScrapeRateLimiter,
            ScrapeRateLimiter targetedScrapeRateLimiter,
            Duration scrapeInterval,
            Clock clock) {
        this(
                versionSources,
                scrapeStateStore,
                scrapeLock,
                fullScrapeRateLimiter,
                targetedScrapeRateLimiter,
                scrapeInterval,
                clock,
                DEFAULT_SCRAPE_CONCURRENCY);
    }

    // Visible for testing: additionally accepts an explicit concurrency cap so tests can drive the
    // bounded-parallel scrape loop with a small cap (issue 04).
    public ApplicationVersionService(
            VersionSources versionSources,
            ScrapeStateStore scrapeStateStore,
            ScrapeLock scrapeLock,
            ScrapeRateLimiter fullScrapeRateLimiter,
            ScrapeRateLimiter targetedScrapeRateLimiter,
            Duration scrapeInterval,
            Clock clock,
            int concurrencyCap) {
        this.versionSources = versionSources;
        this.scrapeStateStore = scrapeStateStore;
        this.scrapeLock = scrapeLock;
        this.fullScrapeRateLimiter = fullScrapeRateLimiter;
        this.targetedScrapeRateLimiter = targetedScrapeRateLimiter;
        this.scrapeInterval = scrapeInterval;
        this.clock = clock;
        this.concurrencyCap = concurrencyCap;
    }

    @Override
    public List<VersionApplication> getApplications() {
        Optional<ScrapeSnapshot> snapshot = scrapeStateStore.read();

        if (snapshot.isPresent() && isFresh(snapshot.get())) {
            return snapshot.get().applications();
        }

        return scrapeUnderLockOrServeSnapshot(snapshot);
    }

    @Override
    public ScrapeStatus triggerScrape() {
        // A manual trigger bypasses the staleness check entirely: always try to win the lock.
        // Lock-first ordering: a lost lock returns IN_PROGRESS and NEVER consults the budget, so a
        // lost trigger spends no slot. Only the lock winner spends a slot from the rolling-window
        // budget — and on RATE_LIMITED it releases the lock without scraping or writing.
        if (!scrapeLock.tryAcquire()) {
            return ScrapeStatus.inProgress();
        }
        ScrapeRateLimiter.Decision budget = spendBudgetOrReleaseLock(fullScrapeRateLimiter);
        if (!budget.allowed()) {
            return ScrapeStatus.rateLimited((int) budget.retryAfter());
        }
        ScrapeResult result = scrapeWriteAndRelease();
        return ScrapeStatus.scraped(
                result.attempted(),
                result.failed(),
                budget.remaining(),
                (int) budget.windowResetsIn(),
                result.targetResults());
    }

    @Override
    public ScrapeStatus targetedScrape(List<ScrapeTarget> targets) {
        // Same lock-first/budget-first ordering as triggerScrape: a lost lock spends no budget; a
        // lock winner that loses the budget check releases the lock without merging or writing.
        if (!scrapeLock.tryAcquire()) {
            return ScrapeStatus.inProgress();
        }
        ScrapeRateLimiter.Decision budget = spendBudgetOrReleaseLock(targetedScrapeRateLimiter);
        if (!budget.allowed()) {
            return ScrapeStatus.rateLimited((int) budget.retryAfter());
        }
        List<TargetResult> results = mergeWriteAndRelease(targets);
        return ScrapeStatus.scraped(results, budget.remaining(), (int) budget.windowResetsIn());
    }

    private List<TargetResult> mergeWriteAndRelease(List<ScrapeTarget> targets) {
        try {
            return mergeAndWrite(targets);
        } finally {
            scrapeLock.release();
        }
    }

    /**
     * Reads the current snapshot, resolves each target against the configured sources (isolating
     * per-target failures), splices the results over the snapshot's applications, and writes the
     * merged list back re-supplying the existing {@code lastAttemptAt} so the fleet-wide staleness
     * clock is not advanced. An empty snapshot has no {@code lastAttemptAt} to preserve, so a
     * definitely-stale one is written instead, keeping a subsequent plain read scraping the fleet.
     */
    private List<TargetResult> mergeAndWrite(List<ScrapeTarget> targets) {
        Optional<ScrapeSnapshot> snapshot = scrapeStateStore.read();
        Map<String, ApplicationSources> sourcesByName = indexSourcesByName();
        List<VersionApplication> merged = new ArrayList<>(lastKnownApplications(snapshot));
        List<TargetResult> results = new ArrayList<>();

        for (ScrapeTarget target : targets) {
            results.add(resolveTarget(target, sourcesByName, merged));
        }

        Instant attemptAt = snapshot.map(ScrapeSnapshot::lastAttemptAt).orElse(Instant.EPOCH);
        scrapeStateStore.write(merged, attemptAt);
        return results;
    }

    private Map<String, ApplicationSources> indexSourcesByName() {
        Map<String, ApplicationSources> byName = new HashMap<>();
        for (ApplicationSources app : versionSources.applicationSources()) {
            byName.put(app.name(), app);
        }
        return byName;
    }

    private TargetResult resolveTarget(
            ScrapeTarget target, Map<String, ApplicationSources> sourcesByName, List<VersionApplication> merged) {
        ApplicationSources app = sourcesByName.get(target.name());
        if (app == null) {
            return TargetResult.failure(target.name(), target.side(), "not monitored");
        }

        try {
            Optional<VersionApplication> existing = findByName(merged, target.name());
            Side effectiveSide = effectiveSide(target.side(), existing.isPresent());
            VersionApplication resolved = resolveVersionApplication(app, existing, effectiveSide);
            spliceApplication(merged, resolved);
            return TargetResult.success(target.name(), effectiveSide);
        } catch (Exception e) {
            logger.warn("Skipping target '{}' this targeted scrape: {}", target.name(), e.getMessage());
            return TargetResult.failure(target.name(), target.side(), e.getMessage());
        }
    }

    // A single-side target for an app not yet in the snapshot is upgraded to BOTH: half a
    // VersionApplication cannot be persisted.
    private Side effectiveSide(Side requested, boolean appExistsInSnapshot) {
        if (!appExistsInSnapshot && requested != Side.BOTH) {
            return Side.BOTH;
        }
        return requested;
    }

    private VersionApplication resolveVersionApplication(
            ApplicationSources app, Optional<VersionApplication> existing, Side side) {
        VersionValue current = switch (side) {
            case CURRENT, BOTH -> app.current().version();
            case LATEST -> existing.orElseThrow().current();
        };
        VersionValue latest = switch (side) {
            case LATEST, BOTH -> app.latest().version();
            case CURRENT -> existing.orElseThrow().latest();
        };
        return new VersionApplication(app.name(), current, latest);
    }

    private Optional<VersionApplication> findByName(List<VersionApplication> apps, String name) {
        return apps.stream().filter(a -> a.name().equals(name)).findFirst();
    }

    private void spliceApplication(List<VersionApplication> apps, VersionApplication replacement) {
        for (int i = 0; i < apps.size(); i++) {
            if (apps.get(i).name().equals(replacement.name())) {
                apps.set(i, replacement);
                return;
            }
        }
        apps.add(replacement);
    }

    private ScrapeRateLimiter.Decision spendBudgetOrReleaseLock(ScrapeRateLimiter rateLimiter) {
        ScrapeRateLimiter.Decision budget = rateLimiter.tryAcquire(clock.instant());
        if (!budget.allowed()) {
            scrapeLock.release();
        }
        return budget;
    }

    private boolean isFresh(ScrapeSnapshot snapshot) {
        Duration sinceLastAttempt = Duration.between(snapshot.lastAttemptAt(), clock.instant());
        return sinceLastAttempt.compareTo(scrapeInterval) < 0;
    }

    private List<VersionApplication> scrapeUnderLockOrServeSnapshot(Optional<ScrapeSnapshot> snapshot) {
        if (scrapeLock.tryAcquire()) {
            return scrapeWriteAndRelease().applications();
        }
        return lastKnownApplications(snapshot);
    }

    private ScrapeResult scrapeWriteAndRelease() {
        try {
            return scrapeAndWrite();
        } finally {
            scrapeLock.release();
        }
    }

    private List<VersionApplication> lastKnownApplications(Optional<ScrapeSnapshot> snapshot) {
        return snapshot.map(ScrapeSnapshot::applications).orElseGet(List::of);
    }

    private ScrapeResult scrapeAndWrite() {
        Instant attemptAt = clock.instant();
        ScrapeResult result = scrape();
        scrapeStateStore.write(result.applications(), attemptAt);
        return result;
    }

    /**
     * The scrape loop: reads both sources for every configured app in BOUNDED PARALLEL using virtual
     * threads, gated by a {@link Semaphore} so at most {@code concurrencyCap} reads are in flight
     * simultaneously. Results are assembled in CONFIG ORDER (index-positioned slots) regardless of
     * completion order.
     *
     * <p>Per-app failures are isolated: one app throwing never aborts the scrape. The invariant
     * {@code applications.size() + failed == attempted} holds.
     */
    private ScrapeResult scrape() {
        List<ApplicationSources> apps = versionSources.applicationSources();
        int attempted = apps.size();

        // Index-positioned result slots ensure config-order assembly after parallel completion.
        record Slot(VersionApplication app, TargetResult result) {}
        Slot[] slots = new Slot[attempted];

        int effectiveCap = Math.max(1, concurrencyCap);
        Semaphore sem = new Semaphore(effectiveCap);

        // A per-scrape virtual-thread executor; close() blocks until all tasks complete.
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < attempted; i++) {
                final int idx = i;
                final ApplicationSources app = apps.get(i);
                executor.submit(() -> {
                    try {
                        sem.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        slots[idx] = new Slot(null,
                                TargetResult.failure(app.name(), Side.BOTH, "interrupted"));
                        return;
                    }
                    try {
                        VersionApplication resolved = new VersionApplication(
                                app.name(),
                                app.current().version(),
                                app.latest().version());
                        slots[idx] = new Slot(resolved, TargetResult.success(app.name(), Side.BOTH));
                    } catch (Exception e) {
                        logger.warn("Skipping app '{}' this scrape: {}", app.name(), e.getMessage());
                        slots[idx] = new Slot(null,
                                TargetResult.failure(app.name(), Side.BOTH, failureReason(e)));
                    } finally {
                        sem.release();
                    }
                });
            }
        } // close() awaits all submitted tasks

        // Assemble in config order from the index-positioned slots.
        List<VersionApplication> resolved = new ArrayList<>();
        List<TargetResult> targetResults = new ArrayList<>();
        int failed = 0;

        for (Slot slot : slots) {
            if (slot.app() != null) {
                resolved.add(slot.app());
            } else {
                failed++;
            }
            targetResults.add(slot.result());
        }

        return new ScrapeResult(resolved, attempted, failed, targetResults);
    }

    private String failureReason(Exception e) {
        String message = e.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        return e.getClass().getSimpleName();
    }
}
