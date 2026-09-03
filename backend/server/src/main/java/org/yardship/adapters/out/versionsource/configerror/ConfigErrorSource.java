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
}
