package org.yardship.core.ports.out;

import io.quarkus.runtime.annotations.RegisterForReflection;

import org.yardship.core.domain.primitives.VersionApplication;

import java.util.List;

import static org.yardship.core.domain.primitives.DomainValidator.notNull;

/**
 * Outcome of a single scrape attempt across all configured applications.
 *
 * <p>{@code attempted} is the number of apps the scrape tried to fetch, {@code failed}
 * is how many of those could not be resolved (per-app failures are isolated), and
 * {@code applications} holds the successfully resolved ones. The invariant
 * {@code applications.size() + failed == attempted} should hold.
 */
@RegisterForReflection
public record ScrapeResult(List<VersionApplication> applications, int attempted, int failed) {

    public ScrapeResult {
        notNull(applications);
    }
}
