package org.yardship.core.ports.out;

import java.util.List;

/**
 * Out-port exposing the per-app source pairs the scrape loop runs over.
 *
 * <p>The driven adapter assembles and holds one {@link ApplicationSources} per configured app.
 * {@code ApplicationVersionService} owns scrape orchestration: the loop, per-app failure isolation,
 * and {@code attempted}/{@code failed} counting that assembles the {@link ScrapeResult}.
 *
 * <p>The concrete implementation (the driven-side resolver) builds the list from config at startup
 * and owns the lifecycle of any {@link java.io.Closeable} sources. The core sees only this port.
 */
public interface VersionSources {

    List<ApplicationSources> applicationSources();
}
