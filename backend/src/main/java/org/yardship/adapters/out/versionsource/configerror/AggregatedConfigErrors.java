package org.yardship.adapters.out.versionsource.configerror;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.yardship.core.domain.primitives.ConfigError;
import org.yardship.core.domain.primitives.ConfigErrorScope;
import org.yardship.core.ports.out.ConfigErrors;

import java.util.Collection;
import java.util.List;

/**
 * The driven side of the {@link ConfigErrors} out-port (ADR-0032, ADR-0035):
 * {@code @ApplicationScoped} aggregator that injects every discovered {@link ConfigErrorSource} —
 * CDI discovery by mere existence, exactly as {@code VersionSourceResolver} does for the per-kind
 * factories, so a future error-producing bean needs no central registration.
 *
 * <p>Read-only and built once at construction: every {@link ConfigErrorSource} records its own
 * errors immutably at ITS construction, so by the time anything injects this bean the full set
 * already exists.
 *
 * <p>A Surface never injects it. {@code ConfigErrorService} reads it through the out-port, and a
 * Surface reads the service through {@code ConfigErrorPort}.
 */
@ApplicationScoped
public class AggregatedConfigErrors implements ConfigErrors {

    private final List<ConfigError> all;
    private final int unnamedAppCount;

    @Inject
    public AggregatedConfigErrors(Instance<ConfigErrorSource> sources) {
        this(sources.stream().toList());
    }

    // Visible for testing: lets tests drive this bean with plain fakes and no CDI container.
    public AggregatedConfigErrors(Collection<ConfigErrorSource> sources) {
        this.all = sources.stream()
                .flatMap(source -> source.configErrors().stream())
                .toList();
        this.unnamedAppCount = sources.stream()
                .mapToInt(ConfigErrorSource::unnamedApps)
                .sum();
    }

    /** Every recorded {@link ConfigError}, across every discovered source, in discovery order. */
    @Override
    public List<ConfigError> all() {
        return all;
    }

    /** Every recorded {@link ConfigError} for one application. Empty when that app is unaffected. */
    @Override
    public List<ConfigError> forApp(String applicationName) {
        return all.stream()
                .filter(error -> error.application().equals(applicationName))
                .toList();
    }

    /** Every recorded {@link ConfigError} of one scope, across every application. */
    @Override
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
    @Override
    public int unnamedAppCount() {
        return unnamedAppCount;
    }
}
