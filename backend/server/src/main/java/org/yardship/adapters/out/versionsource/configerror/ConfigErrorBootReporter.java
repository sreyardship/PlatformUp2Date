package org.yardship.adapters.out.versionsource.configerror;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yardship.core.ports.out.VersionSources;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Replaces the per-factory WARNs scattered through Quarkus startup noise (ADR-0032) with exactly
 * ONE aggregate report: a {@link StartupEvent} observer that logs a single WARN naming every
 * {@code (app, scope, reason)} in {@link ConfigErrors}, or nothing at all for a clean config.
 *
 * <p>Observing {@code StartupEvent} — rather than, say, an {@code @Startup} eager bean — guarantees
 * every {@link ConfigErrorSource} bean has already been constructed (and so has already recorded its
 * errors) by the time this observer fires, so completeness comes from CDI's event-firing order
 * rather than from hoping about construction order.
 *
 * <p>Message shape: {@code "3 of 12 monitored applications have configuration errors:"} followed by
 * one line per recorded error. The leading count is the number of DISTINCT affected applications
 * (an app with both a CURRENT and a LATEST error still counts once), out of the total number of
 * monitored applications ({@link VersionSources#applicationSources()}'s size — every configured app
 * gets an {@link org.yardship.core.ports.out.ApplicationSources} entry regardless of whether either
 * side degraded).
 */
@ApplicationScoped
public class ConfigErrorBootReporter {

    private final Logger logger = LoggerFactory.getLogger(ConfigErrorBootReporter.class);

    private final ConfigErrors configErrors;
    private final int totalAppCount;

    // Injecting VersionSources here is purely to read applicationSources().size() as the
    // denominator — but that injection is what forces CDI to eagerly build the whole driven
    // assembly (VersionSourceResolver and everything it resolves) at StartupEvent, rather than on
    // first use. That eagerness is deliberate and load-bearing: config errors must be fully known
    // by boot time for this reporter to say anything, so resolution cannot be lazy. Do not
    // "simplify" this injection away (e.g. by counting apps some other way) without preserving that
    // eager-construction guarantee.
    @Inject
    public ConfigErrorBootReporter(ConfigErrors configErrors, VersionSources versionSources) {
        this(configErrors, versionSources.applicationSources().size());
    }

    // Visible for testing: lets tests drive this bean with a plain ConfigErrors and an explicit
    // total app count, with no CDI container and no StartupEvent plumbing.
    public ConfigErrorBootReporter(ConfigErrors configErrors, int totalAppCount) {
        this.configErrors = configErrors;
        this.totalAppCount = totalAppCount;
    }

    void onStart(@Observes StartupEvent event) {
        report();
    }

    /** Emits the single aggregate WARN, or logs nothing when {@link ConfigErrors} is empty. */
    public void report() {
        List<ConfigError> errors = configErrors.all();
        if (errors.isEmpty()) {
            return;
        }

        Set<String> affectedApps = errors.stream().map(ConfigError::application).collect(Collectors.toSet());

        StringBuilder message = new StringBuilder();
        message.append(affectedApps.size())
                .append(" of ")
                .append(totalAppCount)
                .append(" monitored applications have configuration errors:");
        for (ConfigError error : errors) {
            message.append(System.lineSeparator())
                    .append("  ")
                    .append(error.application())
                    .append(" [")
                    .append(error.scope())
                    .append("]: ")
                    .append(error.reason());
        }

        logger.warn(message.toString());
    }
}
