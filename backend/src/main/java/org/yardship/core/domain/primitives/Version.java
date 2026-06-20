package org.yardship.core.domain.primitives;

import org.semver4j.SemverException;
import org.yardship.core.domain.exceptions.InvalidVersionException;
import org.semver4j.Semver;

import static org.yardship.core.domain.primitives.DomainValidator.notNull;

public class Version {

    protected final Semver semver;

    public Version(String input) {
        input = notNull(input, new InvalidVersionException("Value cannot be null"));
        input = trimInput(input);

        try {
            this.semver = new Semver(input);
        }
        catch (SemverException ex) {
            throw new InvalidVersionException("Unable to parse version input: '" + input + "'");
        }
    }

    private Version(Semver semver) {
        this.semver = semver;
    }

    public boolean isOlderThan(Version comparable) {
        return this.semver.isLowerThan(comparable.semver);
    }

    public enum Diff {
        // Declaration order IS the severity contract: NONE < PATCH < MINOR < MAJOR.
        // isAtLeast compares by ordinal, so reordering these constants would
        // silently redefine "severity" — keep them in ascending severity order.
        NONE, PATCH, MINOR, MAJOR;

        public boolean isAtLeast(Diff other) {
            return this.ordinal() >= other.ordinal();
        }
    }

    public Diff diff(Version other) {
        Semver.VersionDiff versionDiff = this.semver.diff(other.semver);
        return switch (versionDiff) {
            case MAJOR -> Diff.MAJOR;
            case MINOR -> Diff.MINOR;
            case PATCH, PRE_RELEASE -> Diff.PATCH;
            case BUILD -> Diff.NONE;
            case NONE -> Diff.NONE;
        };
    }

    public String value() {
        return semver.getVersion();
    }

    /**
     * Returns a new {@link Version} with the prerelease segment cleared, leaving build metadata
     * and {@code this} untouched. A version with no prerelease segment is returned unchanged
     * (as a new instance wrapping an equal {@link Semver}).
     */
    public Version withoutPreRelease() {
        return new Version(semver.withClearedPreRelease());
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Version that)) {
            return false;
        }
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

    private String trimInput(String input) {
        input = input.trim();
        return input.replaceAll("^[vV]+", "");
    }
}
