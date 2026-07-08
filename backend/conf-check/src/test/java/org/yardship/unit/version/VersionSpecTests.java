package org.yardship.unit.version;

import org.junit.jupiter.api.Test;
import org.yardship.confcheck.version.VersionSpec;
import org.yardship.core.domain.primitives.VersionScheme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link VersionSpec} is the shared scheme-resolution machinery every scheme-aware subcommand
 * builds on. These are the fast unit tests for its fail-fast contract; the actual parsing
 * behaviour is exercised through {@code RegexExtractionValidation} tests since that's where the
 * resulting {@link org.yardship.core.domain.primitives.VersionParser} is actually used.
 */
class VersionSpecTests {

    @Test
    void semver_doesNotRequireCalverFormat() {
        VersionSpec spec = VersionSpec.of(VersionScheme.SEMVER, null);

        assertEquals(VersionScheme.SEMVER, spec.scheme());
    }

    @Test
    void calver_withValidFormat_succeeds() {
        VersionSpec spec = VersionSpec.of(VersionScheme.CALVER, "YY.0M");

        assertEquals(VersionScheme.CALVER, spec.scheme());
        assertEquals("2024.03", spec.parser().parse("2024.03").value());
    }

    @Test
    void calver_withoutFormat_throwsVersionSpecException() {
        assertThrows(VersionSpec.VersionSpecException.class,
                () -> VersionSpec.of(VersionScheme.CALVER, null),
                "CALVER without a calver-format must fail fast, not defer to first parse");
    }

    @Test
    void calver_withBlankFormat_throwsVersionSpecException() {
        assertThrows(VersionSpec.VersionSpecException.class,
                () -> VersionSpec.of(VersionScheme.CALVER, "   "));
    }

    @Test
    void calver_withUnknownToken_throwsVersionSpecException() {
        assertThrows(VersionSpec.VersionSpecException.class,
                () -> VersionSpec.of(VersionScheme.CALVER, "NOT-A-REAL-TOKEN"));
    }
}
