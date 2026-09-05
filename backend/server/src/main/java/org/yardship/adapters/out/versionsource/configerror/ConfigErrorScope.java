package org.yardship.adapters.out.versionsource.configerror;

/**
 * What a recorded {@link ConfigError} breaks (ADR-0032). Fixes the blast radius of a single
 * per-app configuration defect:
 *
 * <ul>
 *   <li>{@link #CURRENT} / {@link #LATEST} — that side of the app degrades to a
 *       {@code Failed*Source}; the other side keeps reading normally.</li>
 *   <li>{@link #APP} — both sides degrade, because the Version scheme is declared once per app and
 *       shared by both legs (a broken scheme makes neither leg's value commensurable). Produced by
 *       {@code VersionParsers}, not by {@code VersionSourceResolver} — slice 03.</li>
 *   <li>{@link #CHANGELOG} — nothing about the scrape degrades; the app keeps reading both sides
 *       normally and only loses its Changelog link. Produced by {@code ChangelogTemplates} — slice
 *       03.</li>
 * </ul>
 *
 * <p>This slice (01) only ever produces {@link #CURRENT} and {@link #LATEST} errors, via
 * {@code VersionSourceResolver}. All four values exist from the start so the type is stable for
 * every later slice.
 */
public enum ConfigErrorScope {
    CURRENT,
    LATEST,
    APP,
    CHANGELOG
}
