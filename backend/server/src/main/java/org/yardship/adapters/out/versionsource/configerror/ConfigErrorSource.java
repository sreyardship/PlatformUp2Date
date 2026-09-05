package org.yardship.adapters.out.versionsource.configerror;

import java.util.List;

/**
 * "I am a startup bean that interprets config and may find it defective" (ADR-0032). Discovered by
 * mere existence as a CDI bean — exactly the discovery idiom {@code VersionSourceResolver} already
 * uses for the per-kind factories — so a future error-producing bean is picked up by
 * {@link ConfigErrors} without editing a central file.
 *
 * <p>Implementors record their own {@link ConfigError}s immutably at construction and return the
 * same list on every call; there is no live/observable state here. Known implementors:
 * {@code VersionSourceResolver} ({@link ConfigErrorScope#CURRENT} / {@link ConfigErrorScope#LATEST},
 * this slice), and — from slice 03 — {@code VersionParsers} ({@link ConfigErrorScope#APP}) and
 * {@code ChangelogTemplates} ({@link ConfigErrorScope#CHANGELOG}).
 */
public interface ConfigErrorSource {

    /** Every config error this bean found while interpreting its slice of config, if any. */
    List<ConfigError> configErrors();

    /**
     * How many configured apps this bean dropped for having no {@code name} (issue 02 / ADR-0032).
     * An unnamed app has no identity to record a {@link ConfigError} under — it cannot be a
     * {@code configErrors} entry, so this is a separate, unlabelled count rather than a fourth
     * {@link ConfigErrorScope}. Defaults to zero so existing/future sources that never drop apps
     * (e.g. {@code VersionParsers}, {@code ChangelogTemplates}) need not implement it. Only
     * {@code VersionSourceResolver} overrides this today, since it alone iterates every configured
     * app (named or not) to build {@link org.yardship.core.ports.out.ApplicationSources}.
     */
    default int unnamedApps() {
        return 0;
    }
}
