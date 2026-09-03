package org.yardship.adapters.out.versionsource.configerror;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.Collection;
import java.util.List;

/**
 * The single read model every Surface projects for configuration defects (ADR-0032):
 * {@code @ApplicationScoped} aggregator that injects every discovered {@link ConfigErrorSource} —
 * CDI discovery by mere existence, exactly as {@code VersionSourceResolver} does for the per-kind
 * factories, so a future error-producing bean needs no central registration.
 *
 * <p>Read-only and built once at construction: every {@link ConfigErrorSource} records its own
 * errors immutably at ITS construction, so by the time anything injects {@code ConfigErrors} the
 * full set already exists.
 */
@ApplicationScoped
public class ConfigErrors {

    private final List<ConfigError> all;
    private final int unnamedAppCount;

    @Inject
    public ConfigErrors(Instance<ConfigErrorSource> sources) {
        this(sources.stream().toList());
    }

    // Visible for testing: lets tests drive this bean with plain fakes and no CDI container.
    public ConfigErrors(Collection<ConfigErrorSource> sources) {
        this.all = sources.stream()
                .flatMap(source -> source.configErrors().stream())
                .toList();
        this.unnamedAppCount = sources.stream()
                .mapToInt(ConfigErrorSource::unnamedApps)
                .sum();
    }

    /** Every recorded {@link ConfigError}, across every discovered source, in discovery order. */
    public List<ConfigError> all() {
        return all;
    }

    /** Every recorded {@link ConfigError} for one application. Empty when that app is unaffected. */
    public List<ConfigError> forApp(String applicationName) {
        return all.stream()
                .filter(error -> error.application().equals(applicationName))
                .toList();
    }

    /** Every recorded {@link ConfigError} of one scope, across every application. */
    public List<ConfigError> forScope(ConfigErrorScope scope) {
        return all.stream()
                .filter(error -> error.scope() == scope)
                .toList();
    }

    /**
     * Total count of configured apps dropped fleet-wide for having no {@code name} (issue 02 /
     * ADR-0032), summed across every discovered {@link ConfigErrorSource}. Backs the aggregate boot
     * report line and the unlabelled {@code pu2d_config_unnamed_apps} metric — the only two places
     * an unnamed app is visible at all, since it cannot be a {@link ConfigError} entry.
     */
    public int unnamedAppCount() {
        return unnamedAppCount;
    }
}
