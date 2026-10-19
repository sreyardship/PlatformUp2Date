package org.yardship.core.domain.primitives;

/**
 * Domain service that turns a raw version string into a {@link VersionValue}.
 *
 * <p>One instance is built per monitored app (from its configured {@link VersionScheme}, plus a
 * {@code calver-format} when the scheme is {@link VersionScheme#CALVER}, a
 * {@code dotnet-compare-build} flag when it is {@link VersionScheme#DOTNET}, or a
 * {@code semver-optional-patch} flag when it is {@link VersionScheme#SEMVER}) and shared by both the
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
    private final boolean dotnetCompareBuild; // read only for DOTNET
    private final boolean optionalPatch; // read only for SEMVER

    /**
     * Builds a parser for a scheme that needs no REQUIRED extra configuration. A {@code DOTNET}
     * parser built this way compares the build component, the documented default.
     *
     * @throws IllegalArgumentException if {@code scheme} is {@link VersionScheme#CALVER} — calver
     *                                  requires a {@code calver-format}; use
     *                                  {@link #VersionParser(VersionScheme, String)}.
     */
    public VersionParser(VersionScheme scheme) {
        this(scheme, true);
    }

    /**
     * Builds a parser, supplying the {@code dotnet-compare-build} flag used when {@code scheme} is
     * {@link VersionScheme#DOTNET}. For other schemes the flag is ignored.
     *
     * @throws IllegalArgumentException if {@code scheme} is {@link VersionScheme#CALVER} — calver
     *                                  requires a {@code calver-format}, which this constructor
     *                                  cannot supply.
     */
    public VersionParser(VersionScheme scheme, boolean dotnetCompareBuild) {
        if (scheme == VersionScheme.CALVER) {
            throw new IllegalArgumentException(
                    "A CALVER VersionParser requires a calver-format; none was supplied.");
        }
        this.scheme = scheme;
        this.calverFormat = null;
        this.dotnetCompareBuild = dotnetCompareBuild;
        this.optionalPatch = false;
    }

    private VersionParser(boolean optionalPatch) {
        this.scheme = VersionScheme.SEMVER;
        this.calverFormat = null;
        this.dotnetCompareBuild = true;
        this.optionalPatch = optionalPatch;
    }

    /**
     * Builds a {@link VersionScheme#SEMVER} parser, supplying the {@code semver-optional-patch} flag.
     * {@code false} is today's strict semver, identical to {@code new VersionParser(SEMVER)};
     * {@code true} also accepts {@code major.minor[-pre][+build]}, treated as {@code major.minor.0}
     * for comparison while displaying the string as published (ADR-0041).
     */
    public static VersionParser semver(boolean optionalPatch) {
        return new VersionParser(optionalPatch);
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
        this.dotnetCompareBuild = true;
        this.optionalPatch = false;
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
            case SEMVER -> new SemverVersion(raw, optionalPatch);
            case CALVER -> new CalverVersion(raw, calverFormat);
            case DOTNET -> new DotnetVersion(raw, dotnetCompareBuild);
        };
    }

    /**
     * The {@link VersionScheme} this parser is configured for.
     */
    public VersionScheme scheme() {
        return scheme;
    }
}
