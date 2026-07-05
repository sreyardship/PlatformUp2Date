package org.yardship.unit.core.domain.primitives;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.yardship.core.domain.exceptions.InvalidVersionException;
import org.yardship.core.domain.primitives.SemverVersion;
import org.yardship.core.domain.primitives.VersionScheme;
import org.yardship.core.domain.primitives.VersionValue;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behavior suite for {@link SemverVersion}, the semver-backed {@link VersionValue}. Ported verbatim
 * from the former {@code VersionTests} (deleted alongside the old {@code Version} class) — every
 * assertion is semantically identical; only the concrete type ({@code SemverVersion}) and the Diff
 * home ({@code VersionValue.Diff}) changed.
 *
 * <p>This is a pure domain unit test — no Quarkus context needed.
 */
public class SemverVersionTests {

    @Test
    void SemverVersion_throwsInvalidVersionException_whenInputIsNull() {
        assertThrows(InvalidVersionException.class,
                () -> new SemverVersion(null));
    }

    @ParameterizedTest
    @MethodSource("invalidInputs")
    void SemverVersion_throwsInvalidVersionException_whenInputIsInvalid(String input) {
        assertThrows(InvalidVersionException.class,
                () -> new SemverVersion(input));
    }

    @Test
    void SemverVersion_trimsAnyLeadingVCharacter() {
        // Arrange
        String expectedValue = "1.2.34";
        String inputStringWithLowerV = "v" + expectedValue;
        String inputStringWithUpperV = "V" + expectedValue;

        // Act
        SemverVersion someVersion = new SemverVersion(inputStringWithLowerV);
        SemverVersion anotherVersion = new SemverVersion(inputStringWithUpperV);

        // Assert
        assertEquals(expectedValue, someVersion.value());
        assertEquals(expectedValue, anotherVersion.value());
    }

    @Test
    void isOlderThan_returnsTrue_whenComparableIsNewer() {
        // Arrange
        SemverVersion oldVersion = new SemverVersion("1.2.3");
        SemverVersion sameVersion = new SemverVersion("1.2.3");
        SemverVersion newVersion = new SemverVersion("1.2.9");

        // Act & Assert
        assertTrue(oldVersion.isOlderThan(newVersion));
        assertFalse(oldVersion.isOlderThan(sameVersion));
        assertFalse(newVersion.isOlderThan(oldVersion));
    }

    @Test
    void diffIsAtLeast_isTrue_whenSeverityIsEqual() {
        assertTrue(VersionValue.Diff.NONE.isAtLeast(VersionValue.Diff.NONE));
        assertTrue(VersionValue.Diff.PATCH.isAtLeast(VersionValue.Diff.PATCH));
        assertTrue(VersionValue.Diff.MINOR.isAtLeast(VersionValue.Diff.MINOR));
        assertTrue(VersionValue.Diff.MAJOR.isAtLeast(VersionValue.Diff.MAJOR));
    }

    @Test
    void diffIsAtLeast_isTrue_whenThisIsMoreSevere() {
        // Ordering contract: NONE < PATCH < MINOR < MAJOR
        assertTrue(VersionValue.Diff.PATCH.isAtLeast(VersionValue.Diff.NONE));
        assertTrue(VersionValue.Diff.MINOR.isAtLeast(VersionValue.Diff.PATCH));
        assertTrue(VersionValue.Diff.MINOR.isAtLeast(VersionValue.Diff.NONE));
        assertTrue(VersionValue.Diff.MAJOR.isAtLeast(VersionValue.Diff.MINOR));
        assertTrue(VersionValue.Diff.MAJOR.isAtLeast(VersionValue.Diff.PATCH));
        assertTrue(VersionValue.Diff.MAJOR.isAtLeast(VersionValue.Diff.NONE));
    }

    @Test
    void diffIsAtLeast_isFalse_whenThisIsLessSevere() {
        assertFalse(VersionValue.Diff.NONE.isAtLeast(VersionValue.Diff.PATCH));
        assertFalse(VersionValue.Diff.NONE.isAtLeast(VersionValue.Diff.MINOR));
        assertFalse(VersionValue.Diff.NONE.isAtLeast(VersionValue.Diff.MAJOR));
        assertFalse(VersionValue.Diff.PATCH.isAtLeast(VersionValue.Diff.MINOR));
        assertFalse(VersionValue.Diff.PATCH.isAtLeast(VersionValue.Diff.MAJOR));
        assertFalse(VersionValue.Diff.MINOR.isAtLeast(VersionValue.Diff.MAJOR));
    }

    @Test
    void withoutPreRelease_clearsThePreReleaseSegment_whenPresent() {
        // Arrange
        SemverVersion versionWithPreRelease = new SemverVersion("2.11.1-6b7ecba1");

        // Act
        VersionValue result = versionWithPreRelease.withoutPreRelease();

        // Assert
        assertEquals("2.11.1", result.value());
    }

    @Test
    void withoutPreRelease_returnsAnUnchangedCopy_whenNoPreReleaseIsPresent() {
        // Arrange
        SemverVersion versionWithoutPreRelease = new SemverVersion("2.11.1");

        // Act
        VersionValue result = versionWithoutPreRelease.withoutPreRelease();

        // Assert
        assertEquals("2.11.1", result.value());
    }

    @Test
    void withoutPreRelease_preservesBuildMetadata() {
        // Arrange
        SemverVersion versionWithPreReleaseAndBuild = new SemverVersion("2.11.1-rc.1+build5");

        // Act
        VersionValue result = versionWithPreReleaseAndBuild.withoutPreRelease();

        // Assert
        assertEquals("2.11.1+build5", result.value());
    }

    @Test
    void withoutPreRelease_returnsANewInstance_leavingTheOriginalUnchanged() {
        // Arrange
        SemverVersion original = new SemverVersion("2.11.1-6b7ecba1");

        // Act
        VersionValue result = original.withoutPreRelease();

        // Assert
        assertEquals("2.11.1-6b7ecba1", original.value(), "SemverVersion is immutable: the original must be untouched");
        assertEquals("2.11.1", result.value());
    }

    @Test
    void isOlderThan_isFalseBothWays_whenVersionsDifferOnlyInBuildMetadata() {
        // Arrange
        SemverVersion buildAbc = new SemverVersion("1.2.3+abc");
        SemverVersion buildDef = new SemverVersion("1.2.3+def");

        // Act & Assert
        assertFalse(buildAbc.isOlderThan(buildDef),
                "Build metadata must not affect precedence (semver spec §10)");
        assertFalse(buildDef.isOlderThan(buildAbc),
                "Build metadata must not affect precedence (semver spec §10)");
    }

    @Test
    void isOlderThan_isFalseBothWays_whenOnlyOneVersionHasBuildMetadata() {
        // Arrange
        SemverVersion withoutBuild = new SemverVersion("1.2.3");
        SemverVersion withBuild = new SemverVersion("1.2.3+build5");

        // Act & Assert
        assertFalse(withoutBuild.isOlderThan(withBuild),
                "Build metadata must not affect precedence (semver spec §10)");
        assertFalse(withBuild.isOlderThan(withoutBuild),
                "Build metadata must not affect precedence (semver spec §10)");
    }

    @Test
    void diff_isNone_whenVersionsDifferOnlyInBuildMetadata() {
        // Arrange
        SemverVersion buildAbc = new SemverVersion("1.2.3+abc");
        SemverVersion buildDef = new SemverVersion("1.2.3+def");

        // Act & Assert
        // Per semver spec §10, build metadata is ignored for precedence/drift purposes.
        // FINDING (if this fails): SemverVersion.diff() maps semver4j's BUILD VersionDiff to
        // Diff.PATCH alongside PRE_RELEASE, so a build-metadata-only difference is
        // currently reported as Diff.PATCH rather than Diff.NONE.
        assertEquals(VersionValue.Diff.NONE, buildAbc.diff(buildDef));
    }

    @Test
    void diff_isNone_whenOnlyOneVersionHasBuildMetadata() {
        // Arrange
        SemverVersion withoutBuild = new SemverVersion("1.2.3");
        SemverVersion withBuild = new SemverVersion("1.2.3+build5");

        // Act & Assert
        // See FINDING note above: this currently maps to Diff.PATCH via the BUILD case.
        assertEquals(VersionValue.Diff.NONE, withoutBuild.diff(withBuild));
    }

    @Test
    void diff_isUnaffectedByBuildMetadata_whenAPreReleaseIsAlsoPresent() {
        // Arrange
        SemverVersion preReleaseOnly = new SemverVersion("1.2.3-rc.1");
        SemverVersion preReleaseWithBuild = new SemverVersion("1.2.3-rc.1+build5");

        // Act
        VersionValue.Diff diffWithoutBuild = preReleaseOnly.diff(new SemverVersion("1.2.3"));
        VersionValue.Diff diffWithBuild = preReleaseWithBuild.diff(new SemverVersion("1.2.3"));

        // Assert
        // Adding build metadata alongside an existing prerelease must not change the
        // reported drift severity versus the same comparison without build metadata.
        assertEquals(diffWithoutBuild, diffWithBuild,
                "Build metadata must not change drift severity when a prerelease is present");
    }

    @Test
    void equals_treatsVersionsAsDifferent_whenTheyDifferOnlyInBuildMetadata() {
        // Arrange
        SemverVersion buildAbc = new SemverVersion("1.2.3+abc");
        SemverVersion buildDef = new SemverVersion("1.2.3+def");

        // Act & Assert
        // NOTE: this pins semver4j's actual equals() behaviour, which is stricter than
        // precedence/diff: it considers build metadata significant for equality even
        // though build metadata does not affect ordering or (per spec) drift severity.
        // This is documented here so a future change to equals() is a deliberate choice,
        // not an accident.
        assertNotEquals(buildAbc, buildDef);
    }

    // ---- preReleaseSegment() accessor -----------------------------------------------------------

    @Test
    void preReleaseSegment_returnsEmpty_whenVersionHasNoPrerelease() {
        assertEquals(java.util.Optional.empty(), new SemverVersion("1.22.0").preReleaseSegment(),
                "1.22.0 has no prerelease segment — must return Optional.empty()");
    }

    @Test
    void preReleaseSegment_returnsSinglePartPrerelease_forAlpineVariant() {
        assertEquals(java.util.Optional.of("alpine"),
                new SemverVersion("1.22.0-alpine").preReleaseSegment(),
                "1.22.0-alpine → prerelease segment is 'alpine'");
    }

    @Test
    void preReleaseSegment_returnsSinglePartPrerelease_forAlpine316Variant() {
        // semver4j parses "alpine3.16" as a single prerelease identifier (no dot separator),
        // so it returns ["alpine3.16"]. Dot-joining one element yields "alpine3.16".
        assertEquals(java.util.Optional.of("alpine3.16"),
                new SemverVersion("1.22.0-alpine3.16").preReleaseSegment(),
                "1.22.0-alpine3.16 → prerelease segment is 'alpine3.16'");
    }

    @Test
    void preReleaseSegment_returnsDotJoined_forMultiPartPrerelease() {
        // semver4j splits "rc.1" on '.' into ["rc","1"]; dot-joining must give "rc.1".
        assertEquals(java.util.Optional.of("rc.1"),
                new SemverVersion("1.22.0-rc.1").preReleaseSegment(),
                "1.22.0-rc.1 → prerelease segment is 'rc.1' (dot-joined)");
    }

    @Test
    void preReleaseSegment_distinguishes_alpineFrom_alpine316() {
        // Exact-match contract: "alpine" ≠ "alpine3.16" — accessor must expose this distinction.
        var alpine    = new SemverVersion("1.22.0-alpine").preReleaseSegment();
        var alpine316 = new SemverVersion("1.22.0-alpine3.16").preReleaseSegment();

        assertNotEquals(alpine, alpine316,
                "'alpine' and 'alpine3.16' must be distinct prerelease segments");
    }

    // ---- scheme() self-description -----------------------------------------------------------

    @Test
    void scheme_returnsSemver_forAnySemverVersion() {
        // A VersionValue must self-report the scheme it was built under, so an adapter holding
        // just the instance (no live app config) can tell SEMVER from CALVER.
        assertEquals(VersionScheme.SEMVER, new SemverVersion("1.2.3").scheme(),
                "SemverVersion.scheme() must always return VersionScheme.SEMVER");
    }

    private static List<String> invalidInputs() {
        return List.of(
                "Version",
                "<div>Bad Version</div>",
                "; DROP TABLES"
        );
    }
}
