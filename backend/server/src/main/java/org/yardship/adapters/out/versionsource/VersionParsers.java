package org.yardship.adapters.out.versionsource;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yardship.adapters.out.versionsource.configerror.ConfigError;
import org.yardship.adapters.out.versionsource.configerror.ConfigErrorScope;
import org.yardship.adapters.out.versionsource.configerror.ConfigErrorSource;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Per-app {@link VersionParser} lookup, built eagerly at startup from {@link
 * ApplicationConfigLoader}'s per-app {@code version-scheme}/{@code calver-format} config.
 *
 * <p>This is the single place parser construction happens; {@code VersionSourceResolver} consumes
 * this bean instead of building parsers inline, so current and latest legs for a given app always
 * share the exact same parser instance.
 *
 * <p>Per ADR-0032, a calver app with a missing or invalid {@code calver-format} does not fail boot:
 * it records exactly one {@link ConfigErrorScope#APP}-scope {@link ConfigError} instead, and {@link
 * #forApp} returns {@link Optional#empty()} for that app — a legitimate, expected state, since the
 * Version scheme is declared once per app and shared by both legs, so neither leg is parseable.
 */
@ApplicationScoped
@Startup
public class VersionParsers implements ConfigErrorSource {

    private final Logger logger = LoggerFactory.getLogger(VersionParsers.class);

    private final Map<String, VersionParser> parsersByApp;
    private final List<ConfigError> configErrors;

    @Inject
    public VersionParsers(ApplicationConfigLoader configLoader) {
        this(configLoader.apps());
    }

    // Visible for testing: lets tests drive this bean with plain fakes and no CDI container.
    public VersionParsers(List<ApplicationConfigLoader.AppConfig> apps) {
        Map<String, VersionParser> parsers = new HashMap<>();
        List<ConfigError> errors = new ArrayList<>();
        for (ApplicationConfigLoader.AppConfig app : apps) {
            // An app with no name is dropped from the fleet entirely (issue 02 / ADR-0032) — it
            // has no identity to key a parser under, and VersionSourceResolver never resolves it.
            app.name().ifPresent(name -> {
                try {
                    parsers.put(name, buildParser(app));
                } catch (IllegalArgumentException invalidScheme) {
                    errors.add(new ConfigError(name, ConfigErrorScope.APP, invalidScheme.getMessage()));
                } catch (RuntimeException undeclaredDefect) {
                    logger.error("Defect building version parser for app '{}': {}",
                            name, undeclaredDefect.getMessage(), undeclaredDefect);
                    errors.add(new ConfigError(name, ConfigErrorScope.APP, undeclaredDefect.getMessage()));
                }
            });
        }
        this.parsersByApp = Map.copyOf(parsers);
        this.configErrors = List.copyOf(errors);
    }

    /**
     * The resolved parser for {@code appName}, or {@link Optional#empty()} if unconfigured or its
     * version scheme failed to build (see {@link #configErrors()} for the reason).
     */
    public Optional<VersionParser> forApp(String appName) {
        return Optional.ofNullable(parsersByApp.get(appName));
    }

    /**
     * The recorded APP-scope reason {@code appName}'s version scheme failed to build, if any. The
     * seam {@code VersionSourceResolver} consumes to build its app-scope degrade path — an absent
     * parser (see {@link #forApp}) is meaningless to a caller without also knowing why, and this
     * spares every caller from filtering {@link #configErrors()} by application itself.
     */
    public Optional<String> failureReasonForApp(String appName) {
        return configErrors.stream()
                .filter(error -> error.application().equals(appName))
                .map(ConfigError::reason)
                .findFirst();
    }

    /**
     * Every APP-scope config error recorded while resolving the configured apps' version schemes
     * (issue 03 / ADR-0032). {@code VersionSourceResolver} consumes these to decide how to degrade
     * an app whose scheme failed to build — it does not re-report them at CURRENT/LATEST scope.
     */
    @Override
    public List<ConfigError> configErrors() {
        return configErrors;
    }

    private static VersionParser buildParser(ApplicationConfigLoader.AppConfig app) {
        try {
            return switch (app.versionScheme()) {
                case SEMVER -> new VersionParser(VersionScheme.SEMVER);
                case CALVER -> new VersionParser(VersionScheme.CALVER, app.calverFormat().orElse(null));
            };
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Invalid version-scheme configuration for app '" + app.name().orElseThrow()
                            + "': " + ex.getMessage(), ex);
        }
    }
}
