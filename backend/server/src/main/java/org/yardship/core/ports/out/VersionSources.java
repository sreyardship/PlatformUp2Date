package org.yardship.core.ports.out;

import java.util.List;

/**
 * Out-port exposing the per-app source pairs the scrape loop runs over.
 *
 * <p>Replaces the former {@code VersionRepository}: instead of a driven adapter owning the scrape, the
 * adapter only assembles and holds the {@link ApplicationSources} (one per configured app), and
 * {@code ApplicationVersionService} owns the orchestration — the loop, per-app failure isolation,
 * and {@code attempted}/{@code failed} counting that assembles the {@link ScrapeResult}.
 *
 * <p>The concrete implementation (the driven-side resolver) builds the list from config at startup
 * and owns the lifecycle of any {@link java.io.Closeable} sources. The core sees only this port.
 */
public interface VersionSources {

    List<ApplicationSources> applicationSources();
}
