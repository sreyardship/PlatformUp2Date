package org.yardship.adapters.out.versionsource;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yardship.adapters.out.versionsource.configerror.ConfigErrorSource;
import org.yardship.core.domain.primitives.ConfigError;
import org.yardship.core.domain.primitives.ConfigErrorScope;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Per-app {@link VersionParser} lookup, built eagerly at startup from {@link
 * ApplicationConfigLoader}'s per-app {@code version-scheme} config and its scheme-specific
 * companions ({@code calver-format}, {@code dotnet-compare-build}).
 *
 * <p>This is the single place parser construction happens; {@code VersionSourceResolver} consumes
 * this bean instead of building parsers inline, so current and latest legs for a given app always
 * share the exact same parser instance.
 *
 * <p>Per ADR-0032, a calver app with a missing or invalid {@code calver-format} does not fail boot:
 * it records exactly one {@link ConfigErrorScope#APP}-scope {@link ConfigError} instead, and {@link
 * #forApp} returns {@link Optional#empty()} for that app — a legitimate, expected state, since the
 * Version scheme is declared once per app and shared by both legs, so neither leg is parseable.
 *
 * <p>The same APP-scope path refuses a scheme/source-knob combination the scheme can never satisfy:
 * {@code version-scheme: dotnet} with a {@code prerelease-filter} (ADR-0036). See {@link
 * #refusePrereleaseFilterUnderDotnet}.
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
                    refusePrereleaseFilterUnderDotnet(app, name);
                    parsers.put(name, buildParser(app));
                } catch (IllegalArgumentException declaredConfigError) {
                    errors.add(new ConfigError(
                            name, ConfigErrorScope.APP, declaredConfigError.getMessage()));
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

    /**
     * Refuses {@code version-scheme: dotnet} combined with a {@code prerelease-filter} on the latest
     * leg, at APP scope (ADR-0036).
     *
     * <p>{@code prerelease-filter} does not merely strip a segment, it NARROWS the eligible tag set
     * to tags whose pre-release segment equals the filter. {@code DotnetVersion.preReleaseSegment()}
     * is always empty, so no tag can ever match and {@code OciTagSelector} would throw on every
     * scrape, forever, never self-healing — precisely the shape {@code CONTEXT.md} reserves for a
     * configuration error. Ignoring the knob instead would silently widen selection to every tag,
     * the opposite of what the operator asked for.
     *
     * <p>Only {@code oci-registry} reads the knob, but the refusal is keyed on the knob being
     * configured at all rather than on a kind name: under {@code dotnet} the request is unsatisfiable
     * whichever latest kind is declared, and this bean does not dispatch on {@code type} strings.
     * {@code strip-prerelease} is deliberately NOT refused — it is meaningless but harmless, and
     * stays a silent no-op.
     */
    private static void refusePrereleaseFilterUnderDotnet(
            ApplicationConfigLoader.AppConfig app, String name) {
        if (app.versionScheme() != VersionScheme.DOTNET) {
            return;
        }
        Optional<String> prereleaseFilter = app.latest().prereleaseFilter();
        if (prereleaseFilter.isEmpty()) {
            return;
        }
        throw new IllegalArgumentException(
                "App '" + name + "' combines 'version-scheme: dotnet' with a 'prerelease-filter' ("
                        + prereleaseFilter.get() + "). A dotnet version has no pre-release segment, "
                        + "so no tag could ever match the filter. Remove the filter, or use a scheme "
                        + "with pre-release segments.");
    }

    private static VersionParser buildParser(ApplicationConfigLoader.AppConfig app) {
        try {
            return switch (app.versionScheme()) {
                case SEMVER -> new VersionParser(VersionScheme.SEMVER);
                case CALVER -> new VersionParser(VersionScheme.CALVER, app.calverFormat().orElse(null));
                // dotnet-compare-build defaults to true here, beside the code that reads it: the
                // motivating Sonarr 4.0.17 -> 4.0.20 upgrade must read as drift (ADR-0036).
                case DOTNET -> new VersionParser(
                        VersionScheme.DOTNET, app.dotnetCompareBuild().orElse(true));
            };
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Invalid version-scheme configuration for app '" + app.name().orElseThrow()
                            + "': " + ex.getMessage(), ex);
        }
    }
}
