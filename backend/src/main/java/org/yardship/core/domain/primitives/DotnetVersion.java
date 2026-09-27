package org.yardship.core.domain.primitives;

import org.yardship.core.domain.exceptions.InvalidVersionException;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.yardship.core.domain.primitives.DomainValidator.notNull;

/**
 * {@code System.Version}-shaped {@link VersionValue}: two to four non-negative integer components,
 * {@code major.minor[.build[.revision]]}. This is the shape Sonarr, Radarr and Prowlarr publish
 * (e.g. {@code 4.0.20.3014}), which neither semver nor calver can express.
 *
 * <p>Two deliberate departures from the namesake, both recorded in ADR-0036:
 * <ul>
 *   <li><b>Absent trailing components count as zero</b>, so {@code 6.2}, {@code 6.2.0} and
 *       {@code 6.2.0.0} are equal. {@code System.Version} stores undefined components as {@code -1}
 *       and orders {@code 1.1 < 1.1.0 < 1.1.0.0} to separate assembly <em>identities</em> for
 *       binding; PU2D compares published <em>releases</em>, where writing fewer digits is formatting,
 *       not precedence.</li>
 *   <li><b>The revision is never compared.</b> {@code System.Version} documents that assemblies
 *       differing only in revision are "intended to be fully interchangeable", so a revision bump is
 *       not drift. It is still parsed and displayed (and addressable as a changelog placeholder).</li>
 * </ul>
 *
 * <p>The build component IS compared by default; {@code dotnet-compare-build: false} drops it,
 * leaving a major.minor comparison (and making {@link VersionValue.Diff#PATCH} unreachable for that
 * app). {@code equals}/{@code hashCode} are comparison-consistent — they ignore exactly what the
 * comparison ignores — so two values that compare equal are equal. This deliberately does NOT copy
 * {@link SemverVersion}'s split, where build metadata is ignored for ordering but significant for
 * {@code equals}.
 *
 * <p>{@code System.Version} has no pre-release concept: {@link #preReleaseSegment()} is always empty
 * and {@link #withoutPreRelease()} returns {@code this}, which makes a source-level
 * {@code strip-prerelease} a silent no-op.
 *
 * <p>Like the other schemes, a {@code DotnetVersion} only compares with another
 * {@code DotnetVersion}; a foreign {@link VersionValue} throws {@link IllegalArgumentException}. The
 * single per-app {@link VersionParser} guarantees both legs share a scheme, so this cannot occur in
 * production — the guard keeps the sealed-interface contract honest.
 */
public final class DotnetVersion implements VersionValue {

    private static final Pattern GRAMMAR = Pattern.compile("\\d+(?:\\.\\d+){1,3}");
    private static final int COMPONENT_COUNT = 4;

    private final String original;
    private final int[] components; // always length 4; absent trailing components are 0
    private final boolean compareBuild;

    /**
     * Parses {@code input} with the build component compared — the default, because Sonarr's
     * {@code 4.0.17} → {@code 4.0.20} is a same-major, same-minor upgrade that must read as drift.
     *
     * @throws InvalidVersionException if {@code input} is null or is not two to four non-negative
     *                                 integers separated by dots (an optional leading {@code v} or
     *                                 {@code V} is stripped first). A bad version string degrades a
     *                                 single app's scrape, never the boot.
     */
    public DotnetVersion(String input) {
        this(input, true);
    }

    /**
     * Parses {@code input}, comparing the build component only when {@code compareBuild} is
     * {@code true} (the app's {@code dotnet-compare-build} knob).
     *
     * @throws InvalidVersionException as documented on {@link #DotnetVersion(String)}.
     */
    public DotnetVersion(String input, boolean compareBuild) {
        String trimmed = VersionStrings.stripDecoration(
                notNull(input, new InvalidVersionException("Value cannot be null")));
        if (!GRAMMAR.matcher(trimmed).matches()) {
            throw new InvalidVersionException(
                    "Unable to parse dotnet version input: '" + input + "'. Expected two to four "
                            + "non-negative integer components (major.minor[.build[.revision]]).");
        }
        this.original = trimmed;
        this.components = parseComponents(trimmed, input);
        this.compareBuild = compareBuild;
    }

    @Override
    public boolean isOlderThan(VersionValue comparable) {
        return compare(castToDotnetVersion(comparable)) < 0;
    }

    @Override
    public VersionValue.Diff diff(VersionValue other) {
        DotnetVersion that = castToDotnetVersion(other);
        if (components[0] != that.components[0]) {
            return Diff.MAJOR;
        }
        if (components[1] != that.components[1]) {
            return Diff.MINOR;
        }
        if (compareBuild && components[2] != that.components[2]) {
            return Diff.PATCH;
        }
        // A differing revision is never drift, so it has no grade to contribute.
        return Diff.NONE;
    }

    @Override
    public String value() {
        return original;
    }

    @Override
    public VersionScheme scheme() {
        return VersionScheme.DOTNET;
    }

    /** The major component, as its decimal string (e.g. {@code "4"} for {@code 4.0.17.2952}). */
    public String major() {
        return String.valueOf(components[0]);
    }

    /** The minor component, as its decimal string (e.g. {@code "0"} for {@code 4.0.17.2952}). */
    public String minor() {
        return String.valueOf(components[1]);
    }

    /**
     * The build component, as its decimal string (e.g. {@code "17"} for {@code 4.0.17.2952}), or
     * {@code "0"} when the version omits it.
     */
    public String build() {
        return String.valueOf(components[2]);
    }

    /**
     * The revision component, as its decimal string (e.g. {@code "2952"} for {@code 4.0.17.2952}),
     * or {@code "0"} when the version omits it. Displayed and addressable even though it is never
     * compared — a changelog URL frequently needs the full build identifier.
     */
    public String revision() {
        return String.valueOf(components[3]);
    }

    /** Always empty: {@code System.Version} has no pre-release concept. */
    @Override
    public Optional<String> preReleaseSegment() {
        return Optional.empty();
    }

    /** Returns {@code this}: there is no pre-release segment to clear. */
    @Override
    public VersionValue withoutPreRelease() {
        return this;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DotnetVersion that)) {
            return false;
        }
        // Comparison-consistent: exactly the components the comparison reads. Two instances built
        // under different compareBuild settings order the same inputs differently, so they are not
        // the same value (unreachable in production — one parser per app).
        return compareBuild == that.compareBuild && compare(that) == 0;
    }

    @Override
    public int hashCode() {
        return compareBuild
                ? Objects.hash(components[0], components[1], components[2], true)
                : Objects.hash(components[0], components[1], false);
    }

    @Override
    public String toString() {
        return original;
    }

    /**
     * Numeric comparison of major, then minor, then — unless {@code dotnet-compare-build: false} —
     * build. The revision is never read. Uses {@code this.compareBuild}; both legs of an app share
     * one parser, so the two sides can never disagree on it.
     */
    private int compare(DotnetVersion that) {
        int comparedComponents = compareBuild ? 3 : 2;
        for (int i = 0; i < comparedComponents; i++) {
            int comparison = Integer.compare(this.components[i], that.components[i]);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    private static int[] parseComponents(String trimmed, String original) {
        String[] parts = trimmed.split("\\.");
        int[] components = new int[COMPONENT_COUNT];
        for (int i = 0; i < parts.length; i++) {
            try {
                components[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException tooLarge) {
                throw new InvalidVersionException(
                        "Unable to parse dotnet version input: '" + original + "'. Component '"
                                + parts[i] + "' does not fit a 32-bit signed integer.");
            }
        }
        // Absent trailing components stay 0 — the deliberate divergence from System.Version.
        return components;
    }

    private static DotnetVersion castToDotnetVersion(VersionValue v) {
        if (!(v instanceof DotnetVersion dv)) {
            throw new IllegalArgumentException(
                    "DotnetVersion can only be compared with another DotnetVersion, got: "
                            + v.getClass().getSimpleName());
        }
        return dv;
    }
}
