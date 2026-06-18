package org.yardship.adapters.out.versionsource;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yardship.adapters.out.versionclient.ApplicationConfigLoader;
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
 * <p>Replaces the old {@code ApplicationVersionClient}: it no longer scrapes (the service owns the
 * loop) — it only assembles and holds the resolved sources and owns their {@link Closeable}
 * lifecycle. Adding a source kind is a new factory bean and nothing else; this resolver never names
 * a {@code type} string itself.
 *
 * <p>Fail-fast at construction: a duplicate factory {@code type()} or an unknown config {@code type}
 * surfaces as an {@link IllegalStateException} naming the offending type, so a misconfiguration
 * fails the application at startup rather than mid-scrape.
 */
@ApplicationScoped
public class VersionSourceResolver implements VersionSources {

    private final Logger logger = LoggerFactory.getLogger(VersionSourceResolver.class);

    private final List<ApplicationSources> applicationSources;

    @Inject
    public VersionSourceResolver(
            Instance<CurrentVersionSourceFactory> currentFactories,
            Instance<LatestVersionSourceFactory> latestFactories,
            ApplicationConfigLoader configLoader) {
        this(currentFactories.stream().toList(), latestFactories.stream().toList(), configLoader.apps());
    }

    // Visible for testing: lets tests drive the resolver with plain fakes and no CDI container.
    public VersionSourceResolver(
            Collection<CurrentVersionSourceFactory> currentFactories,
            Collection<LatestVersionSourceFactory> latestFactories,
            List<ApplicationConfigLoader.AppConfig> apps) {
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
        CurrentVersionSource current = factoryFor(currentByType, app.current().type()).create(app.current());
        LatestVersionSource latest = factoryFor(latestByType, app.latest().type()).create(app.latest());
        return new ApplicationSources(app.name(), current, latest);
    }

    private static <F> F factoryFor(Map<String, F> byType, String type) {
        F factory = byType.get(type);
        if (factory == null) {
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
