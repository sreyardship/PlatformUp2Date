package org.yardship.core.domain.primitives;

/**
 * The version abstraction shared by every version source and the domain. Sealed to the known
 * schemes — {@link SemverVersion} and {@link CalverVersion} — keeping exhaustive switches honest.
 *
 * <p>Both the {@code current} and {@code latest} legs for a given app use the SAME
 * {@link VersionParser} instance, which means the two sides always produce commensurable
 * {@code VersionValue} instances that can be safely compared.
 */
public sealed interface VersionValue permits SemverVersion, CalverVersion {

    /** {@code true} if this version is strictly older (lower precedence) than {@code comparable}. */
    boolean isOlderThan(VersionValue comparable);

    /**
     * The drift between this version and {@code other}: the highest field that differs, mapped
     * to the {@link Diff} severity enum. Build metadata is ignored for severity purposes.
     */
    Diff diff(VersionValue other);

    /** The canonical string form of this version (e.g. {@code "1.2.3"} or {@code "1.2.3+build5"}). */
    String value();

    /**
     * The {@link VersionScheme} this instance was built under (e.g. {@link VersionScheme#SEMVER}
     * for {@link SemverVersion}). Lets an adapter holding only a {@code VersionValue} instance
     * later reconstruct an equivalent one from its raw string, without consulting live app config.
     */
    VersionScheme scheme();

    /**
     * The pre-release segment of this version, or {@link java.util.Optional#empty()} when there is
     * none. For semver this is the dot-joined prerelease (e.g. {@code 1.22.0-rc.1} → {@code "rc.1"},
     * {@code 1.22.0-alpine} → {@code "alpine"}); for calver it is the {@code MODIFIER} token when the
     * format has one and the version carries it. Used by sources that filter/select by prerelease
     * variant (e.g. {@code oci-registry}).
     */
    java.util.Optional<String> preReleaseSegment();

    /**
     * Returns a new {@link VersionValue} with the pre-release segment cleared, leaving build
     * metadata and {@code this} untouched. A version with no pre-release segment is returned
     * unchanged (as a new instance wrapping an equal underlying value).
     */
    VersionValue withoutPreRelease();

    /**
     * Drift severity between two versions.
     *
     * <p>Declaration order IS the severity contract: {@code NONE < PATCH < MINOR < MAJOR}.
     * {@link #isAtLeast} compares by ordinal, so reordering these constants would silently
     * redefine "severity" — keep them in ascending severity order.
     */
    enum Diff {
        NONE, PATCH, MINOR, MAJOR;

        /** {@code true} if {@code this} severity is at least as severe as {@code other}. */
        public boolean isAtLeast(Diff other) {
            return this.ordinal() >= other.ordinal();
        }
    }
}
