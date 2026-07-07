package org.yardship.core.domain.primitives;

/**
 * The version scheme (format) used by a monitored application.
 *
 * <p>The scheme is configured per app and drives which {@link VersionParser} is built for it.
 * Both the current and latest legs of the same app share ONE parser, so they always produce
 * commensurable {@link VersionValue} instances.
 *
 * <p>SmallRye Config maps enum names case-insensitively, so {@code semver} in YAML binds to
 * {@code SEMVER} here.
 */
public enum VersionScheme {
    SEMVER,
    CALVER
}
