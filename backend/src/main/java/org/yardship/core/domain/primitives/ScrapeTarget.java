package org.yardship.core.domain.primitives;

import io.quarkus.runtime.annotations.RegisterForReflection;

import static org.yardship.core.domain.primitives.DomainValidator.notEmpty;
import static org.yardship.core.domain.primitives.DomainValidator.notNull;

/**
 * One requested unit of work in a targeted scrape: refresh {@code side} of the app named
 * {@code name}. Supplied by the caller of {@link org.yardship.core.ports.in.ApplicationVersionPort#targetedScrape}.
 */
@RegisterForReflection
public record ScrapeTarget(String name, Side side) {

    public ScrapeTarget {
        notEmpty(name);
        notNull(side);
    }
}
