package org.yardship.core.ports.out;

import static org.yardship.core.domain.primitives.DomainValidator.notNull;

/**
 * One configured application paired with its two resolved version sources.
 *
 * <p>This is the unit the scrape loop iterates over: for each app the service reads
 * {@code current.version()} and {@code latest.version()}, isolating per-app failures. The pair is
 * assembled once at startup by the driven-side resolver from config; the core only sees the
 * already-resolved capabilities, never their {@code type} or construction.
 */
public record ApplicationSources(String name, CurrentVersionSource current, LatestVersionSource latest) {

    public ApplicationSources {
        notNull(name);
        notNull(current);
        notNull(latest);
    }
}
