package org.yardship.unit.core.domain.primitives;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.yardship.core.domain.exceptions.InvalidVersionException;
import org.yardship.core.domain.primitives.Version;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class VersionTests {

    @Test
    void Version_throwsInvalidVersionException_whenInputIsNull() {
        assertThrows(InvalidVersionException.class,
                () -> new Version(null));
    }

    @ParameterizedTest
    @MethodSource("invalidInputs")
    void Version_throwsInvalidVersionException_whenInputIsInvalid(String input) {
        assertThrows(InvalidVersionException.class,
                () -> new Version(input));
    }

    @Test
    void Version_trimsAnyLeadingVCharacter() {
        // Arrange
        String expectedValue = "1.2.34";
        String inputStringWithLowerV = "v" + expectedValue;
        String inputStringWithUpperV = "V" + expectedValue;

        // Act
        Version someVersion = new Version(inputStringWithLowerV);
        Version anotherVersion = new Version(inputStringWithUpperV);

        // Assert
        assertEquals(expectedValue, someVersion.value());
        assertEquals(expectedValue, anotherVersion.value());
    }

    @Test
    void isOlderThan_returnsTrue_whenComparableIsNewer() {
        // Arrange
        Version oldVersion = new Version("1.2.3");
        Version sameVersion = new Version("1.2.3");
        Version newVersion = new Version("1.2.9");

        // Act & Assert
        assertTrue(oldVersion.isOlderThan(newVersion));
        assertFalse(oldVersion.isOlderThan(sameVersion));
        assertFalse(newVersion.isOlderThan(oldVersion));
    }

    @Test
    void diffIsAtLeast_isTrue_whenSeverityIsEqual() {
        assertTrue(Version.Diff.NONE.isAtLeast(Version.Diff.NONE));
        assertTrue(Version.Diff.PATCH.isAtLeast(Version.Diff.PATCH));
        assertTrue(Version.Diff.MINOR.isAtLeast(Version.Diff.MINOR));
        assertTrue(Version.Diff.MAJOR.isAtLeast(Version.Diff.MAJOR));
    }

    @Test
    void diffIsAtLeast_isTrue_whenThisIsMoreSevere() {
        // Ordering contract: NONE < PATCH < MINOR < MAJOR
        assertTrue(Version.Diff.PATCH.isAtLeast(Version.Diff.NONE));
        assertTrue(Version.Diff.MINOR.isAtLeast(Version.Diff.PATCH));
        assertTrue(Version.Diff.MINOR.isAtLeast(Version.Diff.NONE));
        assertTrue(Version.Diff.MAJOR.isAtLeast(Version.Diff.MINOR));
        assertTrue(Version.Diff.MAJOR.isAtLeast(Version.Diff.PATCH));
        assertTrue(Version.Diff.MAJOR.isAtLeast(Version.Diff.NONE));
    }

    @Test
    void diffIsAtLeast_isFalse_whenThisIsLessSevere() {
        assertFalse(Version.Diff.NONE.isAtLeast(Version.Diff.PATCH));
        assertFalse(Version.Diff.NONE.isAtLeast(Version.Diff.MINOR));
        assertFalse(Version.Diff.NONE.isAtLeast(Version.Diff.MAJOR));
        assertFalse(Version.Diff.PATCH.isAtLeast(Version.Diff.MINOR));
        assertFalse(Version.Diff.PATCH.isAtLeast(Version.Diff.MAJOR));
        assertFalse(Version.Diff.MINOR.isAtLeast(Version.Diff.MAJOR));
    }

    @Test
    void withoutPreRelease_clearsThePreReleaseSegment_whenPresent() {
        // Arrange
        Version versionWithPreRelease = new Version("2.11.1-6b7ecba1");

        // Act
        Version result = versionWithPreRelease.withoutPreRelease();

        // Assert
        assertEquals("2.11.1", result.value());
    }

    @Test
    void withoutPreRelease_returnsAnUnchangedCopy_whenNoPreReleaseIsPresent() {
        // Arrange
        Version versionWithoutPreRelease = new Version("2.11.1");

        // Act
        Version result = versionWithoutPreRelease.withoutPreRelease();

        // Assert
        assertEquals("2.11.1", result.value());
    }

    @Test
    void withoutPreRelease_preservesBuildMetadata() {
        // Arrange
        Version versionWithPreReleaseAndBuild = new Version("2.11.1-rc.1+build5");

        // Act
        Version result = versionWithPreReleaseAndBuild.withoutPreRelease();

        // Assert
        assertEquals("2.11.1+build5", result.value());
    }

    @Test
    void withoutPreRelease_returnsANewInstance_leavingTheOriginalUnchanged() {
        // Arrange
        Version original = new Version("2.11.1-6b7ecba1");

        // Act
        Version result = original.withoutPreRelease();

        // Assert
        assertEquals("2.11.1-6b7ecba1", original.value(), "Version is immutable: the original must be untouched");
        assertEquals("2.11.1", result.value());
    }

    @Test
    void isOlderThan_isFalseBothWays_whenVersionsDifferOnlyInBuildMetadata() {
        // Arrange
        Version buildAbc = new Version("1.2.3+abc");
        Version buildDef = new Version("1.2.3+def");

        // Act & Assert
        assertFalse(buildAbc.isOlderThan(buildDef),
                "Build metadata must not affect precedence (semver spec §10)");
        assertFalse(buildDef.isOlderThan(buildAbc),
                "Build metadata must not affect precedence (semver spec §10)");
    }

    @Test
    void isOlderThan_isFalseBothWays_whenOnlyOneVersionHasBuildMetadata() {
        // Arrange
        Version withoutBuild = new Version("1.2.3");
        Version withBuild = new Version("1.2.3+build5");

        // Act & Assert
        assertFalse(withoutBuild.isOlderThan(withBuild),
                "Build metadata must not affect precedence (semver spec §10)");
        assertFalse(withBuild.isOlderThan(withoutBuild),
                "Build metadata must not affect precedence (semver spec §10)");
    }

    @Test
    void diff_isNone_whenVersionsDifferOnlyInBuildMetadata() {
        // Arrange
        Version buildAbc = new Version("1.2.3+abc");
        Version buildDef = new Version("1.2.3+def");

        // Act & Assert
        // Per semver spec §10, build metadata is ignored for precedence/drift purposes.
        // FINDING (if this fails): Version.diff() maps semver4j's BUILD VersionDiff to
        // Diff.PATCH alongside PRE_RELEASE, so a build-metadata-only difference is
        // currently reported as Diff.PATCH rather than Diff.NONE.
        assertEquals(Version.Diff.NONE, buildAbc.diff(buildDef));
    }

    @Test
    void diff_isNone_whenOnlyOneVersionHasBuildMetadata() {
        // Arrange
        Version withoutBuild = new Version("1.2.3");
        Version withBuild = new Version("1.2.3+build5");

        // Act & Assert
        // See FINDING note above: this currently maps to Diff.PATCH via the BUILD case.
        assertEquals(Version.Diff.NONE, withoutBuild.diff(withBuild));
    }

    @Test
    void diff_isUnaffectedByBuildMetadata_whenAPreReleaseIsAlsoPresent() {
        // Arrange
        Version preReleaseOnly = new Version("1.2.3-rc.1");
        Version preReleaseWithBuild = new Version("1.2.3-rc.1+build5");

        // Act
        Version.Diff diffWithoutBuild = preReleaseOnly.diff(new Version("1.2.3"));
        Version.Diff diffWithBuild = preReleaseWithBuild.diff(new Version("1.2.3"));

        // Assert
        // Adding build metadata alongside an existing prerelease must not change the
        // reported drift severity versus the same comparison without build metadata.
        assertEquals(diffWithoutBuild, diffWithBuild,
                "Build metadata must not change drift severity when a prerelease is present");
    }

    @Test
    void equals_treatsVersionsAsDifferent_whenTheyDifferOnlyInBuildMetadata() {
        // Arrange
        Version buildAbc = new Version("1.2.3+abc");
        Version buildDef = new Version("1.2.3+def");

        // Act & Assert
        // NOTE: this pins semver4j's actual equals() behaviour, which is stricter than
        // precedence/diff: it considers build metadata significant for equality even
        // though build metadata does not affect ordering or (per spec) drift severity.
        // This is documented here so a future change to equals() is a deliberate choice,
        // not an accident.
        assertNotEquals(buildAbc, buildDef);
    }

    private static List<String> invalidInputs() {
        return List.of(
                "Version",
                "<div>Bad Version</div>",
                "; DROP TABLES"
        );
    }
}
