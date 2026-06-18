package org.yardship.core.services;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yardship.core.ports.out.ApplicationSources;
import org.yardship.core.ports.out.ScrapeResult;
import org.yardship.core.domain.primitives.ScrapeSnapshot;
import org.yardship.core.domain.primitives.VersionApplication;
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
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class ApplicationVersionService implements ApplicationVersionPort {

    private final Logger logger = LoggerFactory.getLogger(ApplicationVersionService.class);

    private final VersionSources versionSources;
    private final ScrapeStateStore scrapeStateStore;
    private final ScrapeLock scrapeLock;
    private final ScrapeRateLimiter scrapeRateLimiter;
    private final Duration scrapeInterval;
    private final Clock clock;

    @Inject
    public ApplicationVersionService(
            VersionSources versionSources,
            ScrapeStateStore scrapeStateStore,
            ScrapeLock scrapeLock,
            ScrapeRateLimiter scrapeRateLimiter,
            @ConfigProperty(name = "platform-config.scrape-interval") Duration scrapeInterval) {
        this(versionSources, scrapeStateStore, scrapeLock, scrapeRateLimiter, scrapeInterval, Clock.systemUTC());
    }

    // Visible for testing: lets tests drive the staleness clock deterministically.
    public ApplicationVersionService(
            VersionSources versionSources,
            ScrapeStateStore scrapeStateStore,
            ScrapeLock scrapeLock,
            ScrapeRateLimiter scrapeRateLimiter,
            Duration scrapeInterval,
            Clock clock) {
        this.versionSources = versionSources;
        this.scrapeStateStore = scrapeStateStore;
        this.scrapeLock = scrapeLock;
        this.scrapeRateLimiter = scrapeRateLimiter;
        this.scrapeInterval = scrapeInterval;
        this.clock = clock;
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
        ScrapeRateLimiter.Decision budget = spendBudgetOrReleaseLock();
        if (!budget.allowed()) {
            return ScrapeStatus.rateLimited((int) budget.retryAfter());
        }
        ScrapeResult result = scrapeWriteAndRelease();
        return ScrapeStatus.scraped(
                result.attempted(), result.failed(), budget.remaining(), (int) budget.windowResetsIn());
    }

    private ScrapeRateLimiter.Decision spendBudgetOrReleaseLock() {
        ScrapeRateLimiter.Decision budget = scrapeRateLimiter.tryAcquire(clock.instant());
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
     * The scrape loop: for each configured app read both sources, isolate per-app failures, and
     * count {@code attempted}/{@code failed}. A single source throwing is caught and counted in
     * {@code failed} — it does NOT abort the scrape or propagate. The invariant
     * {@code applications.size() + failed == attempted} holds.
     */
    private ScrapeResult scrape() {
        List<VersionApplication> resolved = new ArrayList<>();
        int attempted = 0;
        int failed = 0;

        for (ApplicationSources app : versionSources.applicationSources()) {
            attempted++;
            try {
                resolved.add(new VersionApplication(
                        app.name(),
                        app.current().version(),
                        app.latest().version()));
            } catch (Exception e) {
                failed++;
                logger.warn("Skipping app '{}' this scrape: {}", app.name(), e.getMessage());
            }
        }

        return new ScrapeResult(resolved, attempted, failed);
    }
}
