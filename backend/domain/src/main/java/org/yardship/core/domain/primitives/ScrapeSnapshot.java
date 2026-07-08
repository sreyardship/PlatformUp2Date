package org.yardship.core.domain.primitives;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;

import static org.yardship.core.domain.primitives.DomainValidator.notNull;

/**
 * The shared, Valkey-held view that {@code GET /api/v1/version} reads and a scrape writes.
 *
 * <p>{@code applications} are the last successfully scraped apps; {@code lastAttemptAt} is
 * when the scrape that produced this snapshot was attempted — the service uses it to decide
 * staleness against the injected {@link java.time.Clock} and configured scrape-interval.
 */
@RegisterForReflection
public record ScrapeSnapshot(List<VersionApplication> applications, Instant lastAttemptAt) {

    public ScrapeSnapshot {
        notNull(applications);
        notNull(lastAttemptAt);
    }
}
