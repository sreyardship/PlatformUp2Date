package org.yardship.core.ports.in;

import org.yardship.core.domain.primitives.ConfigError;

import java.util.List;

/**
 * Driving port: what is misconfigured about the fleet, and about one Application (ADR-0032,
 * ADR-0035).
 *
 * <p>This is the use case behind {@code list_misconfigured_applications}, the {@code configErrors}
 * field on the REST payload, and {@code pu2d_config_error{application,scope}} — every Surface reads
 * a Config error through here rather than reaching into the driven adapter that records it.
 *
 * <p>A Config error is known when the fleet's Version sources are assembled, before any version is
 * read, so these reads are answers about configuration and not about a Scrape. A Failed scrape is a
 * separate question, answered by {@link ApplicationVersionPort}.
 */
public interface ConfigErrorPort {

    /** Every recorded Config error for one Application. Empty when that Application is unaffected. */
    List<ConfigError> configErrorsFor(String applicationName);

    /** Every recorded Config error across the whole fleet, in the order they were recorded. */
    List<ConfigError> allConfigErrors();

    /**
     * How many configured Applications were dropped fleet-wide for having no {@code name}
     * (ADR-0032). The one number on this port that no Application owns: an unnamed Application
     * cannot be named by a {@link ConfigError} entry, so it is counted instead.
     */
    int unnamedAppCount();
}
