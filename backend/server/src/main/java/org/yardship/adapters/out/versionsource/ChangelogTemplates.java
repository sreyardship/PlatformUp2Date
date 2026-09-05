package org.yardship.adapters.out.versionsource;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yardship.adapters.out.versionsource.configerror.ConfigError;
import org.yardship.adapters.out.versionsource.configerror.ConfigErrorScope;
import org.yardship.adapters.out.versionsource.configerror.ConfigErrorSource;
import org.yardship.core.domain.primitives.CalverFormat;
import org.yardship.core.domain.primitives.ChangelogTemplate;
import org.yardship.core.domain.primitives.VersionScheme;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Per-app {@link ChangelogTemplate} lookup (ADR-0021), built eagerly at startup from
 * {@link ApplicationConfigLoader}'s {@code changelog-url} config.
 *
 * <p>This is the single lookup consumed by both the REST projection ({@code VersionController} /
 * {@code ApplicationStatus}) and the MCP adapter. The per-app template map is built exactly once
 * here and never duplicated into an adapter.
 *
 * <p>Per ADR-0032, an illegal template does not fail boot: it records exactly one {@link
 * ConfigErrorScope#CHANGELOG}-scope {@link ConfigError} instead, and {@link #forApp} returns
 * {@link Optional#empty()} for that app. This is the one scope where a config error degrades
 * nothing else — the app scrapes normally on both legs and only its Changelog link is absent, the
 * same state ADR-0021 already defines for an app with no template configured at all.
 */
@ApplicationScoped
@Startup
public class ChangelogTemplates implements ConfigErrorSource {

    private final Logger logger = LoggerFactory.getLogger(ChangelogTemplates.class);

    private final Map<String, ChangelogTemplate> templatesByApp;
    private final List<ConfigError> configErrors;

    @Inject
    public ChangelogTemplates(ApplicationConfigLoader configLoader) {
        this(configLoader.apps());
    }

    // Visible for testing: lets tests drive this bean with plain fakes and no CDI container.
    public ChangelogTemplates(List<ApplicationConfigLoader.AppConfig> apps) {
        Map<String, ChangelogTemplate> templates = new HashMap<>();
        List<ConfigError> errors = new ArrayList<>();
        for (ApplicationConfigLoader.AppConfig app : apps) {
            // An app with no name is dropped from the fleet entirely (issue 02 / ADR-0032) — it
            // has no identity to key a template under, and VersionSourceResolver never resolves it.
            // Everything this app's template depends on is read inside the try: once the app has a
            // name to be reported under, no accessor it exposes may take the boot down (ADR-0032).
            app.name().ifPresent(name -> {
                try {
                    Optional<String> rawTemplate = app.changelogUrl();
                    if (rawTemplate.isEmpty()) {
                        return;
                    }
                    // A CALVER app whose calver-format is itself missing/invalid has already had
                    // that defect recorded, at APP scope, by VersionParsers — this app's current
                    // AND latest are both degraded there, so there is no version to render a
                    // changelog link from regardless of whether this template is legal. Skip the
                    // app entirely: no template is registered and nothing is recorded here, so
                    // the defect is reported exactly once, owned by VersionParsers.
                    VersionScheme versionScheme = app.versionScheme();
                    Optional<CalverFormat> calverFormat = usableCalverFormat(app, versionScheme);
                    if (versionScheme == VersionScheme.CALVER && calverFormat.isEmpty()) {
                        return;
                    }
                    templates.put(
                            name, buildTemplate(app, rawTemplate.get(), versionScheme, calverFormat));
                } catch (IllegalArgumentException illegalTemplate) {
                    errors.add(new ConfigError(
                            name, ConfigErrorScope.CHANGELOG, illegalTemplate.getMessage()));
                } catch (RuntimeException undeclaredDefect) {
                    logger.error(
                            "Defect building changelog template for app '{}': {}",
                            name, undeclaredDefect.getMessage(), undeclaredDefect);
                    errors.add(new ConfigError(
                            name, ConfigErrorScope.CHANGELOG, undeclaredDefect.getMessage()));
                }
            });
        }
        this.templatesByApp = Map.copyOf(templates);
        this.configErrors = List.copyOf(errors);
    }

    /** The resolved template for {@code appName}, or {@link Optional#empty()} if unconfigured. */
    public Optional<ChangelogTemplate> forApp(String appName) {
        return Optional.ofNullable(templatesByApp.get(appName));
    }

    /**
     * Every CHANGELOG-scope config error recorded while resolving the configured apps' changelog
     * templates (issue 03 / ADR-0032). This is the one scope where a config error degrades nothing
     * else about the app.
     */
    @Override
    public List<ConfigError> configErrors() {
        return configErrors;
    }

    private static ChangelogTemplate buildTemplate(
            ApplicationConfigLoader.AppConfig app, String rawTemplate,
            VersionScheme versionScheme, Optional<CalverFormat> calverFormat) {
        try {
            return new ChangelogTemplate(rawTemplate, versionScheme, calverFormat);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Invalid 'changelog-url' template for app '" + app.name().orElseThrow()
                            + "': " + ex.getMessage(), ex);
        }
    }

    // Guarded construction of the app's CalverFormat, kept separate from buildTemplate's own
    // "the template itself is illegal" try/catch (which must stay scoped to ChangelogTemplate
    // construction only). Empty for a non-CALVER app, or for a CALVER app whose calver-format is
    // missing/invalid — VersionParsers has already recorded that defect at APP scope, so this bean
    // must not construct a second CalverFormat instance and misattribute the same failure here.
    private static Optional<CalverFormat> usableCalverFormat(
            ApplicationConfigLoader.AppConfig app, VersionScheme versionScheme) {
        if (versionScheme != VersionScheme.CALVER) {
            return Optional.empty();
        }
        try {
            return Optional.of(new CalverFormat(app.calverFormat().orElse(null)));
        } catch (IllegalArgumentException invalidFormat) {
            return Optional.empty();
        }
    }
}
