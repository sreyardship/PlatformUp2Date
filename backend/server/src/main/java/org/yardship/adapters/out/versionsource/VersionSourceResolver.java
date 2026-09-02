package org.yardship.adapters.out.versionsource;
import org.yardship.adapters.out.versionsource.latest.LatestVersionSourceFactory;
import org.yardship.adapters.out.versionsource.current.CurrentVersionSourceFactory;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.ports.out.ApplicationSources;
import org.yardship.core.ports.out.CurrentVersionSource;
import org.yardship.core.ports.out.LatestVersionSource;
import org.yardship.core.ports.out.VersionSources;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Composition root for the driven version sources: turns the CDI-discovered per-kind factories plus
 * the configured apps into one {@link ApplicationSources} pair per app, built once at startup.
 *
 * <p>The resolver only assembles and holds sources and owns their {@link Closeable} lifecycle;
 * {@code ApplicationVersionService} owns the scrape loop. Adding a source kind is a new factory bean
 * and nothing else; this resolver never names a {@code type} string itself — with one bounded
 * exception: {@link #RETIRED_KIND_REPLACEMENTS}, a small, append-only map of retired kind names to
 * their replacement, consulted only on the no-factory-found path so a renamed kind's boot failure
 * explains itself instead of reading like an application defect. It names only kinds that have
 * actually been retired — a closed historical set — so *adding* a new kind still touches no central
 * file; only *retiring* one does.
 *
 * <p>Fail-fast at construction: a duplicate factory {@code type()} or an unknown config {@code type}
 * surfaces as an {@link IllegalStateException} naming the offending type, so a misconfiguration
 * fails the application at startup rather than mid-scrape.
 */
@ApplicationScoped
public class VersionSourceResolver implements VersionSources {

    // Retired version-source kind names, mapped to their replacement. Consulted ONLY on the
    // no-factory-found path in factoryFor: a closed, append-only historical set, not a dispatch
    // table — the resolver still does not name a live `type` string anywhere else. Retiring a kind
    // means adding an entry here; adding a new kind touches no central file, as before.
    private static final Map<String, String> RETIRED_KIND_REPLACEMENTS = Map.of(
            "http", "http-json");

    private final Logger logger = LoggerFactory.getLogger(VersionSourceResolver.class);

    private final List<ApplicationSources> applicationSources;

    private final VersionParsers versionParsers;

    @Inject
    public VersionSourceResolver(
            Instance<CurrentVersionSourceFactory> currentFactories,
            Instance<LatestVersionSourceFactory> latestFactories,
            ApplicationConfigLoader configLoader,
            VersionParsers versionParsers) {
        this(currentFactories.stream().toList(), latestFactories.stream().toList(), configLoader.apps(),
                versionParsers);
    }

    // Visible for testing: lets tests drive the resolver with plain fakes and no CDI container.
    public VersionSourceResolver(
            Collection<CurrentVersionSourceFactory> currentFactories,
            Collection<LatestVersionSourceFactory> latestFactories,
            List<ApplicationConfigLoader.AppConfig> apps,
            VersionParsers versionParsers) {
        this.versionParsers = versionParsers;
        Map<String, CurrentVersionSourceFactory> currentByType =
                indexByType(currentFactories, CurrentVersionSourceFactory::type);
        Map<String, LatestVersionSourceFactory> latestByType =
                indexByType(latestFactories, LatestVersionSourceFactory::type);

        this.applicationSources = apps.stream()
                .map(app -> resolve(app, currentByType, latestByType))
                .toList();
    }

    @Override
    public List<ApplicationSources> applicationSources() {
        return applicationSources;
    }

    private static <F> Map<String, F> indexByType(Collection<F> factories, Function<F, String> type) {
        Map<String, F> byType = new HashMap<>();
        for (F factory : factories) {
            String key = type.apply(factory);
            if (byType.put(key, factory) != null) {
                throw new IllegalStateException(
                        "Duplicate version source factory for type '" + key + "'.");
            }
        }
        return byType;
    }

    private ApplicationSources resolve(
            ApplicationConfigLoader.AppConfig app,
            Map<String, CurrentVersionSourceFactory> currentByType,
            Map<String, LatestVersionSourceFactory> latestByType) {
        // One parser per app, shared by both legs, so current and latest are always commensurable by
        // construction — a cross-scheme comparison cannot occur. Built once, fail-fast at startup, by
        // VersionParsers; every configured app has an entry there, so an absent parser here is a bug.
        VersionParser parser = versionParsers.forApp(app.name()).orElseThrow();
        CurrentVersionSource current =
                factoryFor(currentByType, app.current().type()).create(app.current(), parser);
        LatestVersionSource latest =
                factoryFor(latestByType, app.latest().type()).create(app.latest(), parser);
        return new ApplicationSources(app.name(), current, latest);
    }

    private static <F> F factoryFor(Map<String, F> byType, String type) {
        F factory = byType.get(type);
        if (factory == null) {
            String replacement = RETIRED_KIND_REPLACEMENTS.get(type);
            if (replacement != null) {
                throw new IllegalStateException("The '" + type + "' version source kind was renamed to '"
                        + replacement + "'; update this app's config.");
            }
            throw new IllegalStateException("No version source factory for config type '" + type + "'.");
        }
        return factory;
    }

    @PreDestroy
    void closeSources() {
        for (ApplicationSources app : applicationSources) {
            close(app.current());
            close(app.latest());
        }
    }

    private void close(Object source) {
        if (source instanceof Closeable closeable) {
            try {
                closeable.close();
            } catch (IOException e) {
                logger.warn("Failed to close version source: {}", e.getMessage());
            }
        }
    }
}
