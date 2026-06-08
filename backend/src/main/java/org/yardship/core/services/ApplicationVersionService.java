package org.yardship.core.services;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yardship.core.domain.primitives.VersionApplication;
import org.yardship.core.ports.in.ApplicationVersionPort;
import org.yardship.core.ports.out.VersionRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

@ApplicationScoped
public class ApplicationVersionService implements ApplicationVersionPort {

    private static final Logger logger = LoggerFactory.getLogger(ApplicationVersionService.class);

    private final VersionRepository versionRepository;
    private final Duration scrapeInterval;
    private final Clock clock;

    private volatile List<VersionApplication> cachedApplications = Collections.emptyList();
    private Instant lastAttemptAt;

    @Inject
    public ApplicationVersionService(
            VersionRepository versionRepository,
            @ConfigProperty(name = "platform-config.scrape-interval") Duration scrapeInterval) {
        this(versionRepository, scrapeInterval, Clock.systemUTC());
    }

    // Visible for testing: lets tests drive the staleness clock deterministically.
    public ApplicationVersionService(VersionRepository versionRepository, Duration scrapeInterval, Clock clock) {
        this.versionRepository = versionRepository;
        this.scrapeInterval = scrapeInterval;
        this.clock = clock;
    }

    @Override
    public synchronized List<VersionApplication> getApplications() {
        if (isStale()) {
            refresh();
        }
        return cachedApplications;
    }

    private boolean isStale() {
        return lastAttemptAt == null
                || Duration.between(lastAttemptAt, clock.instant()).compareTo(scrapeInterval) >= 0;
    }

    private void refresh() {
        // Mark the attempt up front so a failing dependency isn't re-hit on every
        // request — at most one refresh attempt per scrape-interval, success or not.
        lastAttemptAt = clock.instant();
        try {
            logger.info("Refreshing version applications");
            cachedApplications = List.copyOf(versionRepository.getAllVersionApplications());
            logger.info("Refreshed {} version applications", cachedApplications.size());
        } catch (Exception e) {
            logger.error("Failed to refresh version applications, keeping previous cache", e);
        }
    }
}
