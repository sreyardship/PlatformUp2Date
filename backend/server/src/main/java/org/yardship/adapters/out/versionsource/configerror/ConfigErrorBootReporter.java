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
 * monitored applications ({@link VersionSources#applicationSources()}'s size — an unnamed app never
 * gets an {@link org.yardship.core.ports.out.ApplicationSources} entry at all (issue 02 / ADR-0032:
 * it is dropped from the fleet entirely, not merely degraded), so it is excluded from this
 * denominator the same way it is excluded from the numerator).
 *
 * <p>When there are no {@link ConfigError}s at all but one or more apps were dropped for having no
 * {@code name}, the "N of M monitored applications" header would be self-contradictory (a "0 of M"
 * header followed by a body line) — so that case gets its own message: just the unnamed-app line,
 * with no leading header.
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

    /**
     * Emits the single aggregate WARN, or logs nothing when {@link ConfigErrors} has neither a
     * recorded {@link ConfigError} nor a dropped unnamed app.
     */
    public void report() {
        List<ConfigError> errors = configErrors.all();
        int unnamedAppCount = configErrors.unnamedAppCount();
        if (errors.isEmpty() && unnamedAppCount == 0) {
            return;
        }

        StringBuilder message = new StringBuilder();
        if (errors.isEmpty()) {
            // No ConfigError exists to build a "N of M ... have configuration errors" header from —
            // only unnamed apps triggered this branch (the guard above). A header claiming zero
            // errors, followed by a body about dropped apps, would contradict itself, so this case
            // gets no header at all: just the unnamed-app line.
            appendUnnamedAppLine(message, unnamedAppCount);
        } else {
            Set<String> affectedApps =
                    errors.stream().map(ConfigError::application).collect(Collectors.toSet());
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
            // Unnamed apps have no identity to list per-entry (issue 02 / ADR-0032) — one summary
            // line instead, appended after the per-error lines.
            if (unnamedAppCount > 0) {
                message.append(System.lineSeparator()).append("  ");
                appendUnnamedAppLine(message, unnamedAppCount);
            }
        }

        logger.warn(message.toString());
    }

    private static void appendUnnamedAppLine(StringBuilder message, int unnamedAppCount) {
        message.append(unnamedAppCount)
                .append(unnamedAppCount == 1 ? " application has" : " applications have")
                .append(" no 'name' configured and ")
                .append(unnamedAppCount == 1 ? "is" : "are")
                .append(" dropped from the fleet entirely.");
    }
}
