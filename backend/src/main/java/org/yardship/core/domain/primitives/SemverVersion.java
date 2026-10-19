package org.yardship.core.domain.primitives;

import org.semver4j.Semver;
import org.semver4j.SemverException;
import org.yardship.core.domain.exceptions.InvalidVersionException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.yardship.core.domain.primitives.DomainValidator.notNull;

/**
 * Semver-backed {@link VersionValue}. Trims a leading {@code v}, rejects unparseable input with
 * {@link InvalidVersionException}, and ignores build metadata for ordering. Build metadata remains
 * significant for {@code equals}/{@code hashCode}, per semver4j's contract.
 *
 * <p>Under {@code semver-optional-patch} (ADR-0041) a two-part {@code major.minor[-pre][+build]}
 * string is also accepted: it is completed to {@code major.minor.0} before semver4j sees it, so
 * ordering, grading, equality and pre-release handling are exactly those of the padded value, while
 * {@link #value()} still displays the string as published.
 */
public final class SemverVersion implements VersionValue {

    // major.minor, then an optional pre-release and/or build suffix — but no third component.
    private static final Pattern TWO_PART = Pattern.compile("^(\\d+\\.\\d+)([-+].*)?$");

    private final Semver semver;
    private final boolean patchOmitted; // display-only: the published string had no patch

    /** Strict semver: a two-part string is rejected. */
    public SemverVersion(String input) {
        this(input, false);
    }

    /**
     * @param patchOptional when {@code true}, a two-part {@code major.minor} string is accepted and
     *                      compared as {@code major.minor.0}; one-part and four-plus-part strings
     *                      are rejected either way.
     */
    public SemverVersion(String input, boolean patchOptional) {
        input = notNull(input, new InvalidVersionException("Value cannot be null"));
        input = VersionStrings.stripDecoration(input);
        Matcher twoPart = patchOptional ? TWO_PART.matcher(input) : null;
        this.patchOmitted = twoPart != null && twoPart.matches();
        String strict = patchOmitted
                ? twoPart.group(1) + ".0" + (twoPart.group(2) == null ? "" : twoPart.group(2))
                : input;
        try {
            this.semver = new Semver(strict);
        } catch (SemverException ex) {
            throw new InvalidVersionException("Unable to parse version input: '" + input + "'");
        }
    }

    // Private constructor used by withoutPreRelease() — skips the trim/validate cycle since the
    // Semver instance was produced internally from an already-valid semver4j value.
    private SemverVersion(Semver semver, boolean patchOmitted) {
        this.semver = semver;
        this.patchOmitted = patchOmitted;
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
        if (!patchOmitted) {
            return semver.getVersion();
        }
        StringBuilder published = new StringBuilder()
                .append(semver.getMajor()).append('.').append(semver.getMinor());
        preReleaseSegment().ifPresent(pre -> published.append('-').append(pre));
        if (!semver.getBuild().isEmpty()) {
            published.append('+').append(String.join(".", semver.getBuild()));
        }
        return published.toString();
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
        return new SemverVersion(semver.withClearedPreRelease(), patchOmitted);
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
        return value();
    }

    private static SemverVersion castToSemverVersion(VersionValue v) {
        if (!(v instanceof SemverVersion sv)) {
            throw new IllegalArgumentException(
                    "SemverVersion can only be compared with another SemverVersion, got: "
                            + v.getClass().getSimpleName());
        }
        return sv;
    }
}
