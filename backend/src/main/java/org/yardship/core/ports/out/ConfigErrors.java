package org.yardship.core.ports.out;

import org.yardship.core.domain.primitives.ConfigError;
import org.yardship.core.domain.primitives.ConfigErrorScope;

import java.util.List;

/**
 * Out-port exposing the Config errors recorded when the fleet's Version sources were assembled
 * (ADR-0032, ADR-0035).
 *
 * <p>The failure half of {@link VersionSources}: that port carries the sources that were assembled,
 * this one carries the ones that could not be. Both are read from the same boot-time pass over the
 * configuration document, so a Config error is known before any version is read and never
 * self-heals.
 *
 * <p>The driven adapter records every Config error immutably at construction and answers every read
 * from that snapshot. The core sees only this port.
 */
public interface ConfigErrors {

    /** Every recorded Config error, across every source, in discovery order. */
    List<ConfigError> all();

    /** Every recorded Config error for one Application. Empty when that Application is unaffected. */
    List<ConfigError> forApp(String applicationName);

    /** Every recorded Config error of one scope, across every Application. */
    List<ConfigError> forScope(ConfigErrorScope scope);

    /**
     * Total count of configured Applications dropped fleet-wide for having no {@code name}
     * (ADR-0032). Fleet-wide rather than per-app, because an unnamed Application cannot be named by
     * a {@link ConfigError} entry.
     */
    int unnamedAppCount();
}
