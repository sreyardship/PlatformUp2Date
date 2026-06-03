package org.yardship.core.services;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yardship.core.domain.primitives.VersionApplication;
import org.yardship.core.ports.in.ApplicationVersionPort;
import org.yardship.core.ports.out.VersionRepository;

import java.util.Collections;
import java.util.List;

@ApplicationScoped
public class ApplicationVersionService implements ApplicationVersionPort {

    private static final Logger logger = LoggerFactory.getLogger(ApplicationVersionService.class);

    @Inject
    VersionRepository versionRepository;

    private volatile List<VersionApplication> cachedApplications = Collections.emptyList();

    @Override
    public List<VersionApplication> getApplications() {
        return cachedApplications;
    }

    public void resetCache() {
        cachedApplications = Collections.emptyList();
    }

    @Scheduled(every = "${platform-config.scrape-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void scrape() {
        try {
            logger.info("Scraping version applications");
            List<VersionApplication> applications = versionRepository.getAllVersionApplications();
            cachedApplications = List.copyOf(applications);
            logger.info("Scraped {} version applications", cachedApplications.size());
        } catch (Exception e) {
            logger.error("Failed to scrape version applications, keeping previous cache", e);
        }
    }
}
