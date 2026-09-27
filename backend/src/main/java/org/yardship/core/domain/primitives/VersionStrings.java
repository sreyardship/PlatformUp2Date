package org.yardship.core.domain.primitives;

/**
 * The string conventions shared by every {@link VersionValue} implementation, kept in one place so
 * two schemes cannot drift apart on what counts as the same version string.
 *
 * <p>Package-private: this is an implementation detail of the value types, not a domain concept an
 * adapter or service may reach for.
 */
final class VersionStrings {

    private VersionStrings() {
    }

    /**
     * Trims surrounding whitespace and strips a leading {@code v}/{@code V} prefix — upstreams tag
     * {@code v1.2.3} and report {@code 1.2.3} for the same release, so the prefix is decoration, not
     * part of the version. {@code SemverVersion} and {@link DotnetVersion} both parse through this,
     * which is why {@code v4.0.20.3014} and {@code 4.0.20.3014} compare equal under either.
     */
    static String stripDecoration(String raw) {
        return raw.trim().replaceAll("^[vV]+", "");
    }
}
