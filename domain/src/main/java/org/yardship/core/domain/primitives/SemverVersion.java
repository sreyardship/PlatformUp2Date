package org.yardship.core.domain.primitives;

import org.semver4j.Semver;
import org.semver4j.SemverException;
import org.yardship.core.domain.exceptions.InvalidVersionException;

import static org.yardship.core.domain.primitives.DomainValidator.notNull;

/**
 * Semver-backed {@link VersionValue}. Wraps a semver4j {@link Semver} and ports the exact
 * semantics of the former {@code Version} class verbatim — constructor v-prefix trim,
 * {@link InvalidVersionException} on unparseable input, build-metadata-ignored-for-ordering
 * semantics, and the {@code equals}/{@code hashCode} behaviour (build metadata IS significant
 * for equality, per semver4j's own contract, even though it is ignored for ordering and drift).
 */
public final class SemverVersion implements VersionValue {

    private final Semver semver;

    public SemverVersion(String input) {
        input = notNull(input, new InvalidVersionException("Value cannot be null"));
        input = trimInput(input);
        try {
            this.semver = new Semver(input);
        } catch (SemverException ex) {
            throw new InvalidVersionException("Unable to parse version input: '" + input + "'");
        }
    }

    // Private constructor used by withoutPreRelease() — skips the trim/validate cycle since the
    // Semver instance was produced internally from an already-valid semver4j value.
    private SemverVersion(Semver semver) {
        this.semver = semver;
    }

    @Override
    public boolean isOlderThan(VersionValue comparable) {
        return this.semver.isLowerThan(castToSemverVersion(comparable).semver);
    }

    @Override
    public VersionValue.Diff diff(VersionValue other) {
        Semver.VersionDiff versionDiff = this.semver.diff(castToSemverVersion(other).semver);
        return switch (versionDiff) {
            case MAJOR -> VersionValue.Diff.MAJOR;
            case MINOR -> VersionValue.Diff.MINOR;
            case PATCH, PRE_RELEASE -> VersionValue.Diff.PATCH;
            case BUILD, NONE -> VersionValue.Diff.NONE;
        };
    }

    @Override
    public String value() {
        return semver.getVersion();
    }

    @Override
    public VersionScheme scheme() {
        return VersionScheme.SEMVER;
    }

    /** The major component, as its displayed decimal string (e.g. {@code "3"} for {@code 3.10.5}). */
    public String major() {
        return String.valueOf(semver.getMajor());
    }

    /** The minor component, as its displayed decimal string (e.g. {@code "10"} for {@code 3.10.5}). */
    public String minor() {
        return String.valueOf(semver.getMinor());
    }

    /** The patch component, as its displayed decimal string (e.g. {@code "5"} for {@code 3.10.5}). */
    public String patch() {
        return String.valueOf(semver.getPatch());
    }

    @Override
    public java.util.Optional<String> preReleaseSegment() {
        java.util.List<String> parts = semver.getPreRelease();
        if (parts == null || parts.isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(String.join(".", parts));
    }

    /**
     * Returns a new {@link SemverVersion} with the pre-release segment cleared, leaving build
     * metadata and {@code this} untouched. A version with no pre-release segment is returned
     * unchanged (as a new instance wrapping an equal {@link Semver}).
     */
    @Override
    public VersionValue withoutPreRelease() {
        return new SemverVersion(semver.withClearedPreRelease());
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof SemverVersion that)) return false;
        return semver.equals(that.semver);
    }

    @Override
    public int hashCode() {
        return semver.hashCode();
    }

    @Override
    public String toString() {
        return semver.toString();
    }

    private static SemverVersion castToSemverVersion(VersionValue v) {
        if (!(v instanceof SemverVersion sv)) {
            throw new IllegalArgumentException(
                    "SemverVersion can only be compared with another SemverVersion, got: "
                            + v.getClass().getSimpleName());
        }
        return sv;
    }

    private static String trimInput(String input) {
        input = input.trim();
        return input.replaceAll("^[vV]+", "");
    }
}
