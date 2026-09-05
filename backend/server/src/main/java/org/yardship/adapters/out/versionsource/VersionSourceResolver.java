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
import java.util.Optional;
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
 * factory's own instance. Either way the rest of the app — and every other app — keeps working.
 *
 * <p>Two construction-time throws remain, and both are defects in our own wiring rather than an
 * operator's config, so both still fail the application at startup: a duplicate factory
 * {@code type()}, surfaced as an {@link IllegalStateException} naming the offending type; and
 * {@link #resolve} finding neither a parser nor a recorded config error for a named app, also an
 * {@link IllegalStateException}, which is unreachable from any config input because
 * {@link VersionParsers} guarantees one or the other for every named app it is given.
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

    // Count of configured apps dropped for having no 'name' (issue 02 / ADR-0032). They cannot be
    // a ConfigError entry (no identity to record one under), so they are counted separately and
    // surfaced only via unnamedApps() -> ConfigErrors.unnamedAppCount() -> the aggregate boot
    // report and the unlabelled pu2d_config_unnamed_apps metric.
    private final int unnamedAppCount;

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

        // An app that binds with no name is dropped from the fleet entirely (issue 02 / ADR-0032):
        // it cannot be a board row, a configErrors entry, or a labelled metric series, because
        // reporting an app requires an identity we do not have. No synthetic or positional name is
        // ever fabricated to work around this. Named siblings in the same file are unaffected.
        List<ApplicationSources> resolved = new ArrayList<>();
        int unnamed = 0;
        for (ApplicationConfigLoader.AppConfig app : apps) {
            if (app.name().isEmpty()) {
                unnamed++;
                continue;
            }
            resolved.add(resolve(app.name().get(), app, currentByType, latestByType));
        }
        this.applicationSources = List.copyOf(resolved);
        this.unnamedAppCount = unnamed;
    }

    @Override
    public List<ApplicationSources> applicationSources() {
        return applicationSources;
    }

    /**
     * Every {@link ConfigError} recorded while resolving the configured apps: one per side (
     * {@link ConfigErrorScope#CURRENT} / {@link ConfigErrorScope#LATEST}) whose factory threw, whose
     * factory itself returned a {@code Failed*Source}, or whose config {@code type} was unknown,
     * retired, or absent. Recorded immutably at construction, in resolution order.
     */
    @Override
    public List<ConfigError> configErrors() {
        return List.copyOf(configErrors);
    }

    /**
     * Count of configured apps dropped for having no {@code name} (issue 02 / ADR-0032). See
     * {@link ConfigErrorSource#unnamedApps()}.
     */
    @Override
    public int unnamedApps() {
        return unnamedAppCount;
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
            String appName,
            ApplicationConfigLoader.AppConfig app,
            Map<String, CurrentVersionSourceFactory> currentByType,
            Map<String, LatestVersionSourceFactory> latestByType) {
        // One parser per app, shared by both legs, so current and latest are always commensurable by
        // construction — a cross-scheme comparison cannot occur. Built once by VersionParsers. An
        // absent parser is now a legitimate, expected state (issue 03 / ADR-0032): the app's version
        // scheme itself failed to build, VersionParsers already recorded exactly one APP-scope
        // ConfigError for it, and this becomes the app-scope degrade path — BOTH sides fail with the
        // scheme's own reason, and nothing is recorded here, so the defect is not reported a second
        // (or third) time. An unnamed app never reaches this method — it is dropped by the
        // constructor loop above before resolve() is ever called for it.
        Optional<VersionParser> parser = versionParsers.forApp(appName);
        if (parser.isEmpty()) {
            String reason = versionParsers.failureReasonForApp(appName).orElseThrow(() ->
                    new IllegalStateException(
                            "VersionParsers has no parser and no recorded config error for app '"
                                    + appName + "': this is a defect in VersionParsers itself."));
            return new ApplicationSources(
                    appName, new FailedCurrentSource(reason), new FailedLatestSource(reason));
        }
        CurrentVersionSource current = resolveCurrent(appName, app, currentByType, parser.get());
        LatestVersionSource latest = resolveLatest(appName, app, latestByType, parser.get());
        return new ApplicationSources(appName, current, latest);
    }

    private CurrentVersionSource resolveCurrent(
            String appName,
            ApplicationConfigLoader.AppConfig app,
            Map<String, CurrentVersionSourceFactory> currentByType,
            VersionParser parser) {
        Optional<String> configuredType = app.current().type();
        if (configuredType.isEmpty()) {
            return degradeCurrent(appName, missingTypeMessage("current"));
        }
        String type = configuredType.get();
        CurrentVersionSourceFactory factory = currentByType.get(type);
        if (factory == null) {
            return degradeCurrent(appName, noFactoryMessage(type));
        }
        try {
            CurrentVersionSource created = factory.create(app.current(), parser);
            if (created instanceof FailedCurrentSource failed) {
                recordCurrent(appName, failed.message());
                return failed;
            }
            return created;
        } catch (IllegalArgumentException declaredConfigError) {
            return degradeCurrent(appName, declaredConfigError.getMessage());
        } catch (RuntimeException undeclaredDefect) {
            logger.error("Defect in current version source factory '{}' for app '{}': {}",
                    type, appName, undeclaredDefect.getMessage(), undeclaredDefect);
            return degradeCurrent(appName, undeclaredDefect.getMessage());
        }
    }

    private LatestVersionSource resolveLatest(
            String appName,
            ApplicationConfigLoader.AppConfig app,
            Map<String, LatestVersionSourceFactory> latestByType,
            VersionParser parser) {
        Optional<String> configuredType = app.latest().type();
        if (configuredType.isEmpty()) {
            return degradeLatest(appName, missingTypeMessage("latest"));
        }
        String type = configuredType.get();
        LatestVersionSourceFactory factory = latestByType.get(type);
        if (factory == null) {
            return degradeLatest(appName, noFactoryMessage(type));
        }
        try {
            LatestVersionSource created = factory.create(app.latest(), parser);
            if (created instanceof FailedLatestSource failed) {
                recordLatest(appName, failed.message());
                return failed;
            }
            return created;
        } catch (IllegalArgumentException declaredConfigError) {
            return degradeLatest(appName, declaredConfigError.getMessage());
        } catch (RuntimeException undeclaredDefect) {
            logger.error("Defect in latest version source factory '{}' for app '{}': {}",
                    type, appName, undeclaredDefect.getMessage(), undeclaredDefect);
            return degradeLatest(appName, undeclaredDefect.getMessage());
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

    // A source with no 'type' configured degrades exactly like an unknown type (noFactoryMessage
    // below) — there is no kind to dispatch to either way (issue 02 / ADR-0032).
    private static String missingTypeMessage(String side) {
        return "The " + side + " version source has no 'type' configured.";
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
