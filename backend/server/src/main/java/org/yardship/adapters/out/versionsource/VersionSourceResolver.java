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
import org.yardship.adapters.out.versionsource.configerror.ConfigError;
import org.yardship.adapters.out.versionsource.configerror.ConfigErrorScope;
import org.yardship.adapters.out.versionsource.configerror.ConfigErrorSource;
import org.yardship.adapters.out.versionsource.current.FailedCurrentSource;
import org.yardship.adapters.out.versionsource.latest.FailedLatestSource;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.ports.out.ApplicationSources;
import org.yardship.core.ports.out.CurrentVersionSource;
import org.yardship.core.ports.out.LatestVersionSource;
import org.yardship.core.ports.out.VersionSources;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
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
 * their replacement, consulted only on the no-factory-found path so a renamed kind's degradation
 * explains itself instead of reading like an application defect. It names only kinds that have
 * actually been retired — a closed historical set — so *adding* a new kind still touches no central
 * file; only *retiring* one does.
 *
 * <p>Per ADR-0032, a config error degrades only the affected app; it never fails the boot. An
 * unknown or retired config {@code type}, any {@code create(...)} that throws, and any
 * {@code create(...)} that itself returns a {@link FailedCurrentSource}/{@link FailedLatestSource}
 * all record exactly one {@link ConfigError} for the affected side, which is then represented by a
 * {@code Failed*Source} that fails every scrape. The first two build that source via
 * {@code degradeCurrent}/{@code degradeLatest}; the third records against, and returns, the
 * factory's own instance. Either way the rest of the app — and every other app — keeps working. The sole remaining construction-time
 * throw is a duplicate factory {@code type()}, which surfaces as an {@link IllegalStateException}
 * naming the offending type: that is a defect in our own wiring, not an operator's config, so it
 * still fails the application at startup.
 */
@ApplicationScoped
public class VersionSourceResolver implements VersionSources, ConfigErrorSource {

    // Retired version-source kind names, mapped to their replacement. Consulted ONLY on the
    // no-factory-found path in factoryFor: a closed, append-only historical set, not a dispatch
    // table — the resolver still does not name a live `type` string anywhere else. Retiring a kind
    // means adding an entry here; adding a new kind touches no central file, as before.
    private static final Map<String, String> RETIRED_KIND_REPLACEMENTS = Map.of(
            "http", "http-json");

    private final Logger logger = LoggerFactory.getLogger(VersionSourceResolver.class);

    private final List<ApplicationSources> applicationSources;

    private final VersionParsers versionParsers;

    private final List<ConfigError> configErrors = new ArrayList<>();

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

    /**
     * Every {@link ConfigError} recorded while resolving the configured apps: one per side (
     * {@link ConfigErrorScope#CURRENT} / {@link ConfigErrorScope#LATEST}) whose factory threw, whose
     * factory itself returned a {@code Failed*Source}, or whose config {@code type} was unknown or
     * retired. Recorded immutably at construction, in resolution order.
     */
    @Override
    public List<ConfigError> configErrors() {
        return List.copyOf(configErrors);
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
        CurrentVersionSource current = resolveCurrent(app, currentByType, parser);
        LatestVersionSource latest = resolveLatest(app, latestByType, parser);
        return new ApplicationSources(app.name(), current, latest);
    }

    private CurrentVersionSource resolveCurrent(
            ApplicationConfigLoader.AppConfig app,
            Map<String, CurrentVersionSourceFactory> currentByType,
            VersionParser parser) {
        String type = app.current().type();
        CurrentVersionSourceFactory factory = currentByType.get(type);
        if (factory == null) {
            return degradeCurrent(app.name(), noFactoryMessage(type));
        }
        try {
            CurrentVersionSource created = factory.create(app.current(), parser);
            if (created instanceof FailedCurrentSource failed) {
                recordCurrent(app.name(), failed.message());
                return failed;
            }
            return created;
        } catch (IllegalArgumentException declaredConfigError) {
            return degradeCurrent(app.name(), declaredConfigError.getMessage());
        } catch (RuntimeException undeclaredDefect) {
            logger.error("Defect in current version source factory '{}' for app '{}': {}",
                    type, app.name(), undeclaredDefect.getMessage(), undeclaredDefect);
            return degradeCurrent(app.name(), undeclaredDefect.getMessage());
        }
    }

    private LatestVersionSource resolveLatest(
            ApplicationConfigLoader.AppConfig app,
            Map<String, LatestVersionSourceFactory> latestByType,
            VersionParser parser) {
        String type = app.latest().type();
        LatestVersionSourceFactory factory = latestByType.get(type);
        if (factory == null) {
            return degradeLatest(app.name(), noFactoryMessage(type));
        }
        try {
            LatestVersionSource created = factory.create(app.latest(), parser);
            if (created instanceof FailedLatestSource failed) {
                recordLatest(app.name(), failed.message());
                return failed;
            }
            return created;
        } catch (IllegalArgumentException declaredConfigError) {
            return degradeLatest(app.name(), declaredConfigError.getMessage());
        } catch (RuntimeException undeclaredDefect) {
            logger.error("Defect in latest version source factory '{}' for app '{}': {}",
                    type, app.name(), undeclaredDefect.getMessage(), undeclaredDefect);
            return degradeLatest(app.name(), undeclaredDefect.getMessage());
        }
    }

    // The one place that knows how a side's config error is recorded. Both the degrade* helpers
    // (which build the Failed*Source themselves) and the branch that adopts a factory's own
    // Failed*Source go through here, so a side error is recorded identically on every path.
    private void recordCurrent(String appName, String message) {
        configErrors.add(new ConfigError(appName, ConfigErrorScope.CURRENT, message));
    }

    private void recordLatest(String appName, String message) {
        configErrors.add(new ConfigError(appName, ConfigErrorScope.LATEST, message));
    }

    private CurrentVersionSource degradeCurrent(String appName, String message) {
        recordCurrent(appName, message);
        return new FailedCurrentSource(message);
    }

    private LatestVersionSource degradeLatest(String appName, String message) {
        recordLatest(appName, message);
        return new FailedLatestSource(message);
    }

    private static String noFactoryMessage(String type) {
        String replacement = RETIRED_KIND_REPLACEMENTS.get(type);
        if (replacement != null) {
            return "The '" + type + "' version source kind was renamed to '"
                    + replacement + "'; update this app's config.";
        }
        return "No version source factory for config type '" + type + "'.";
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
