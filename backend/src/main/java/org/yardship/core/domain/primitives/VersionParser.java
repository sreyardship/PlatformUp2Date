package org.yardship.core.domain.primitives;

/**
 * Domain service that turns a raw version string into a {@link VersionValue}.
 *
 * <p>One instance is built per monitored app (from its configured {@link VersionScheme}, plus a
 * {@code calver-format} when the scheme is {@link VersionScheme#CALVER}) and shared by both the
 * current and latest legs. Sharing ensures the two sides always produce commensurable values that
 * can be compared with each other.
 *
 * <p>Configuration is validated <b>fail-fast at construction</b>: a {@code CALVER} parser built
 * without a usable {@code calver-format} throws immediately (startup), consistent with the resolver's
 * other boot-time checks. A bad <em>version string</em> (as opposed to bad config) is isolated to the
 * {@link #parse(String)} call so it degrades a single app's scrape rather than the boot.
 */
public class VersionParser {

    private final VersionScheme scheme;
    private final CalverFormat calverFormat; // non-null only for CALVER

    /**
     * Builds a parser for a scheme that needs no extra configuration.
     *
     * @throws IllegalArgumentException if {@code scheme} is {@link VersionScheme#CALVER} — calver
     *                                  requires a {@code calver-format}; use the two-arg constructor.
     */
    public VersionParser(VersionScheme scheme) {
        if (scheme == VersionScheme.CALVER) {
            throw new IllegalArgumentException(
                    "A CALVER VersionParser requires a calver-format; none was supplied.");
        }
        this.scheme = scheme;
        this.calverFormat = null;
    }

    /**
     * Builds a parser, supplying the {@code calver-format} used when {@code scheme} is
     * {@link VersionScheme#CALVER}. For other schemes the format is ignored.
     *
     * @throws IllegalArgumentException if {@code scheme} is {@code CALVER} and {@code calverFormat}
     *                                  is null, blank, or contains an unknown token (propagated from
     *                                  {@link CalverFormat}).
     */
    public VersionParser(VersionScheme scheme, String calverFormat) {
        this.scheme = scheme;
        if (scheme == VersionScheme.CALVER) {
            // CalverFormat fail-fasts on null/blank/unknown-token formats — let that surface here.
            this.calverFormat = new CalverFormat(calverFormat);
        } else {
            this.calverFormat = null;
        }
    }

    /**
     * Parse {@code raw} into a {@link VersionValue} appropriate for this parser's scheme.
     *
     * @throws org.yardship.core.domain.exceptions.InvalidVersionException if {@code raw} is null or
     *         not a valid version string for this scheme/format.
     */
    public VersionValue parse(String raw) {
        return switch (scheme) {
            case SEMVER -> new SemverVersion(raw);
            case CALVER -> new CalverVersion(raw, calverFormat);
        };
    }

    /**
     * The {@link VersionScheme} this parser is configured for.
     */
    public VersionScheme scheme() {
        return scheme;
    }
}
