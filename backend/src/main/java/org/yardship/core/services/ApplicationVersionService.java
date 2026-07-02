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
import org.yardship.core.domain.primitives.SideObservation;
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
import java.util.function.Supplier;

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
            VersionApplication resolved = resolveVersionApplication(app, existing, target.side());
            spliceApplication(merged, resolved);
            return TargetResult.success(target.name(), target.side());
        } catch (Exception e) {
            logger.warn("Skipping target '{}' this targeted scrape: {}", target.name(), e.getMessage());
            return TargetResult.failure(target.name(), target.side(), e.getMessage());
        }
    }

    /**
     * Resolves the requested side(s) of an app from its sources. For the untouched side, falls
     * back to the existing snapshot value when present, or a pending (all-empty) {@link SideObservation}
     * when the app is cold (not yet in the snapshot). This allows single-side targeted scrapes to
     * persist a partially-Unresolved {@link VersionApplication} without requiring both sides.
     */
    private VersionApplication resolveVersionApplication(
            ApplicationSources app, Optional<VersionApplication> existing, Side side) {
        Instant now = clock.instant();
        SideObservation pendingIfAbsent = pendingSideObservation();
        SideObservation current = switch (side) {
            case CURRENT, BOTH -> SideObservation.resolved(app.current().version(), now);
            case LATEST -> existing.map(VersionApplication::current).orElse(pendingIfAbsent);
        };
        SideObservation latest = switch (side) {
            case LATEST, BOTH -> SideObservation.resolved(app.latest().version(), now);
            case CURRENT -> existing.map(VersionApplication::latest).orElse(pendingIfAbsent);
        };
        return new VersionApplication(app.name(), current, latest);
    }

    /** A never-attempted, all-empty {@link SideObservation} used as a placeholder for an unread side. */
    private static SideObservation pendingSideObservation() {
        return new SideObservation(Optional.empty(), Optional.empty(), Optional.empty());
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
     * The scrape loop: resolves each side of every configured app independently in BOUNDED PARALLEL
     * using virtual threads, gated by a {@link Semaphore} so at most {@code concurrencyCap} reads
     * are in flight simultaneously. Results are assembled in CONFIG ORDER (index-positioned slots)
     * regardless of completion order.
     *
     * <p>Per-app per-side failures are isolated and merged over the prior snapshot: when a side's
     * source throws and a prior value exists for that side, the prior value + lastSuccessAt are
     * preserved and {@code lastFailureAt} is stamped to now. When a side's source throws and no
     * prior value exists, the side becomes a value-less failed {@link SideObservation} and the app
     * is persisted as Unresolved (not dropped). The invariant {@code applications.size() == attempted}
     * holds; {@code failed} counts apps where at least one side has no value (Unresolved).
     *
     * <p>Fleet {@code lastAttemptAt} is set by the caller ({@link #scrapeAndWrite()}) from the
     * clock, so it always advances on a full scrape regardless of per-side outcomes.
     */
    private ScrapeResult scrape() {
        Optional<ScrapeSnapshot> priorSnapshot = scrapeStateStore.read();
        Map<String, VersionApplication> priorByName = indexApplicationsByName(priorSnapshot);

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
                final VersionApplication prior = priorByName.get(app.name());
                executor.submit(() -> {
                    try {
                        sem.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        SideObservation pending = pendingSideObservation();
                        slots[idx] = new Slot(
                                new VersionApplication(app.name(), pending, pending),
                                TargetResult.failure(app.name(), Side.BOTH, "interrupted"));
                        return;
                    }
                    try {
                        Instant now = clock.instant();
                        SideResolution currentRes = tryResolveSide(
                                app.current()::version,
                                prior != null ? prior.current() : null,
                                now);
                        SideResolution latestRes = tryResolveSide(
                                app.latest()::version,
                                prior != null ? prior.latest() : null,
                                now);
                        VersionApplication resolved = new VersionApplication(
                                app.name(), currentRes.observation(), latestRes.observation());
                        // An app is Unresolved (and counts as failed) when at least one side has no
                        // value — that only happens when a source threw with no prior to fall back on.
                        String failReason = currentRes.failureReason() != null
                                ? currentRes.failureReason()
                                : latestRes.failureReason();
                        TargetResult targetResult = failReason != null
                                ? TargetResult.failure(app.name(), Side.BOTH, failReason)
                                : TargetResult.success(app.name(), Side.BOTH);
                        slots[idx] = new Slot(resolved, targetResult);
                    } catch (Exception e) {
                        // Unexpected failure (not a source error): persist as fully-pending Unresolved.
                        logger.warn("Unexpected error scraping app '{}': {}", app.name(), e.getMessage());
                        SideObservation pending = pendingSideObservation();
                        slots[idx] = new Slot(
                                new VersionApplication(app.name(), pending, pending),
                                TargetResult.failure(app.name(), Side.BOTH, failureReason(e)));
                    } finally {
                        sem.release();
                    }
                });
            }
        } // close() awaits all submitted tasks

        // Assemble in config order. ALL apps are always persisted (including Unresolved ones).
        // failed counts apps whose TargetResult is not succeeded (at least one side has no value).
        List<VersionApplication> resolved = new ArrayList<>();
        List<TargetResult> targetResults = new ArrayList<>();
        int failed = 0;

        for (Slot slot : slots) {
            resolved.add(slot.app());
            if (!slot.result().succeeded()) {
                failed++;
            }
            targetResults.add(slot.result());
        }

        return new ScrapeResult(resolved, attempted, failed, targetResults);
    }

    /**
     * A lightweight result type carrying a resolved {@link SideObservation} and an optional
     * failure reason. {@code failureReason} is non-null only when the source threw AND there was
     * no prior value to fall back on — meaning the side is value-less (Unresolved).
     */
    private record SideResolution(SideObservation observation, String failureReason) {}

    /**
     * Attempts to resolve a side's version from the given {@code source}. On success returns a
     * fresh {@link SideObservation}. On failure:
     * <ul>
     *   <li>If a {@code prior} observation with a value exists, returns a merged observation that
     *       preserves the prior value + lastSuccessAt and stamps {@code lastFailureAt = now}.
     *       The {@code failureReason} is {@code null} — the app is still considered Resolved.</li>
     *   <li>If there is no prior value, returns a value-less failed {@link SideObservation} with
     *       a non-null {@code failureReason} — the app will be Unresolved.</li>
     * </ul>
     */
    private SideResolution tryResolveSide(
            Supplier<VersionValue> source, SideObservation prior, Instant now) {
        try {
            return new SideResolution(SideObservation.resolved(source.get(), now), null);
        } catch (Exception e) {
            if (prior != null && prior.value().isPresent()) {
                // Merge with prior: prior value is kept, app remains Resolved, failure is stamped.
                return new SideResolution(
                        new SideObservation(prior.value(), prior.lastSuccessAt(), Optional.of(now)),
                        null);
            }
            // No prior: value-less failed observation — the side (and app) will be Unresolved.
            return new SideResolution(
                    new SideObservation(Optional.empty(), Optional.empty(), Optional.of(now)),
                    failureReason(e));
        }
    }

    /**
     * Builds a name → {@link VersionApplication} index from the prior snapshot so the full-scrape
     * loop can look up each app's last-known per-side observations in O(1).
     */
    private Map<String, VersionApplication> indexApplicationsByName(Optional<ScrapeSnapshot> snapshot) {
        Map<String, VersionApplication> byName = new HashMap<>();
        snapshot.ifPresent(s -> {
            for (VersionApplication app : s.applications()) {
                byName.put(app.name(), app);
            }
        });
        return byName;
    }

    private String failureReason(Exception e) {
        String message = e.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        return e.getClass().getSimpleName();
    }
}
