package org.yardship.adapters.out.versionsource;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Per-app {@link VersionParser} lookup (issue 01), built EAGERLY at startup from {@link
 * ApplicationConfigLoader}'s per-app {@code version-scheme}/{@code calver-format} config so a
 * calver app with a missing/invalid format fails boot rather than surfacing mid-scrape.
 *
 * <p>This is the single place parser construction happens; {@code VersionSourceResolver} consumes
 * this bean instead of building parsers inline, so current and latest legs for a given app always
 * share the exact same parser instance.
 */
@ApplicationScoped
@Startup
public class VersionParsers {

    private final Map<String, VersionParser> parsersByApp;

    @Inject
    public VersionParsers(ApplicationConfigLoader configLoader) {
        this(configLoader.apps());
    }

    // Visible for testing: lets tests drive this bean with plain fakes and no CDI container.
    public VersionParsers(List<ApplicationConfigLoader.AppConfig> apps) {
        Map<String, VersionParser> parsers = new HashMap<>();
        for (ApplicationConfigLoader.AppConfig app : apps) {
            parsers.put(app.name(), buildParser(app));
        }
        this.parsersByApp = Map.copyOf(parsers);
    }

    /** The resolved parser for {@code appName}, or {@link Optional#empty()} if unconfigured. */
    public Optional<VersionParser> forApp(String appName) {
        return Optional.ofNullable(parsersByApp.get(appName));
    }

    private static VersionParser buildParser(ApplicationConfigLoader.AppConfig app) {
        try {
            return switch (app.versionScheme()) {
                case SEMVER -> new VersionParser(VersionScheme.SEMVER);
                case CALVER -> new VersionParser(VersionScheme.CALVER, app.calverFormat().orElse(null));
            };
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Invalid version-scheme configuration for app '" + app.name() + "': " + ex.getMessage(), ex);
        }
    }
}
