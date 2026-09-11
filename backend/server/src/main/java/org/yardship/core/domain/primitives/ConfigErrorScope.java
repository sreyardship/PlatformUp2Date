package org.yardship.core.domain.primitives;

/**
 * What a recorded {@link ConfigError} breaks (ADR-0032). Fixes the blast radius of a single
 * per-app configuration defect:
 *
 * <ul>
 *   <li>{@link #CURRENT} / {@link #LATEST} — that side of the app degrades to a
 *       {@code Failed*Source}; the other side keeps reading normally.</li>
 *   <li>{@link #APP} — both sides degrade, because the Version scheme is declared once per app and
 *       shared by both legs (a broken scheme makes neither leg's value commensurable). Recorded by
 *       {@code VersionParsers}, not by {@code VersionSourceResolver}.</li>
 *   <li>{@link #CHANGELOG} — nothing about the scrape degrades; the app keeps reading both sides
 *       normally and only loses its Changelog link. Recorded by {@code ChangelogTemplates}.</li>
 * </ul>
 *
 * <p>{@link #CURRENT} and {@link #LATEST} are recorded by {@code VersionSourceResolver}. All four
 * producers name a scope from the adapter layer; the scope itself is domain language (ADR-0035) —
 * the two sides and the Changelog link, both already in {@code CONTEXT.md}.
 */
public enum ConfigErrorScope {
    CURRENT,
    LATEST,
    APP,
    CHANGELOG
}
