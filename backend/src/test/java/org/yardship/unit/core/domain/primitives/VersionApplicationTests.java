package org.yardship.unit.core.domain.primitives;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.yardship.core.domain.exceptions.InvalidDomainObjectException;
import org.yardship.core.domain.primitives.SemverVersion;
import org.yardship.core.domain.primitives.VersionValue;
import org.yardship.core.domain.primitives.VersionApplication;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class VersionApplicationTests {

    private final String validName = "Dummy Application";
    private final VersionValue currentVersion = new SemverVersion("1.2.3");
    private final VersionValue latestVersion = new SemverVersion("3.2.1");

    @Test
    void VersionApplications_needsANameAndValidVersions() {
        assertThrows(InvalidDomainObjectException.class, () ->
                new VersionApplication("", currentVersion, latestVersion));
        assertThrows(InvalidDomainObjectException.class, () ->
                new VersionApplication(null, currentVersion, latestVersion));
        assertThrows(InvalidDomainObjectException.class, () ->
                new VersionApplication(validName, null, latestVersion));
        assertThrows(InvalidDomainObjectException.class, () ->
                new VersionApplication(validName, currentVersion, null));
    }

    @Test
    void VersionApplications_canCheckIfTheyAreOld() {
        // Arrange
        VersionApplication oldApplication = new VersionApplication(validName, currentVersion, latestVersion);
        VersionApplication up2dateApplication = new VersionApplication(validName, latestVersion, latestVersion);

        // Act & Assert
        assertTrue(oldApplication.isOld());
        assertFalse(up2dateApplication.isOld());
    }

    @Test
    void drift_isMajor_whenMajorBehind() {
        VersionApplication app = new VersionApplication(validName, new SemverVersion("1.1.1"), new SemverVersion("2.2.2"));
        assertEquals(VersionValue.Diff.MAJOR, app.drift());
    }

    @Test
    void drift_isMinor_whenMinorBehind() {
        VersionApplication app = new VersionApplication(validName, new SemverVersion("2.1.0"), new SemverVersion("2.2.0"));
        assertEquals(VersionValue.Diff.MINOR, app.drift());
    }

    @Test
    void drift_isPatch_whenPatchBehind() {
        VersionApplication app = new VersionApplication(validName, new SemverVersion("2.2.1"), new SemverVersion("2.2.2"));
        assertEquals(VersionValue.Diff.PATCH, app.drift());
    }

    @Test
    void drift_isNone_whenVersionsEqual() {
        VersionApplication app = new VersionApplication(validName, new SemverVersion("2.2.2"), new SemverVersion("2.2.2"));
        assertEquals(VersionValue.Diff.NONE, app.drift());
    }

    @Test
    void drift_isNone_whenCurrentIsAheadOfLatest() {
        VersionApplication app = new VersionApplication(validName, new SemverVersion("3.0.0"), new SemverVersion("2.0.0"));
        assertEquals(VersionValue.Diff.NONE, app.drift());
    }

    @Test
    void drift_isPatch_whenOnlySuffixDifferenceWhileBehind() {
        VersionApplication app = new VersionApplication(validName, new SemverVersion("2.0.0-rc1"), new SemverVersion("2.0.0"));
        assertEquals(VersionValue.Diff.PATCH, app.drift());
    }

    // hasDriftAtLeast: "does this app drift by at least the given minimum severity?"
    // Drift ordering contract: NONE < PATCH < MINOR < MAJOR.

    @Test
    void hasDriftAtLeast_isFalseAtEveryThreshold_whenCurrent() {
        VersionApplication current = new VersionApplication(validName,
                new SemverVersion("2.2.2"), new SemverVersion("2.2.2"));

        assertFalse(current.hasDriftAtLeast(VersionValue.Diff.PATCH));
        assertFalse(current.hasDriftAtLeast(VersionValue.Diff.MINOR));
        assertFalse(current.hasDriftAtLeast(VersionValue.Diff.MAJOR));
    }

    @Test
    void hasDriftAtLeast_patchApp_meetsOnlyPatchThreshold() {
        VersionApplication patchBehind = new VersionApplication(validName,
                new SemverVersion("2.2.1"), new SemverVersion("2.2.2"));

        assertTrue(patchBehind.hasDriftAtLeast(VersionValue.Diff.PATCH));
        assertFalse(patchBehind.hasDriftAtLeast(VersionValue.Diff.MINOR));
        assertFalse(patchBehind.hasDriftAtLeast(VersionValue.Diff.MAJOR));
    }

    @Test
    void hasDriftAtLeast_minorApp_meetsPatchAndMinorThresholds() {
        VersionApplication minorBehind = new VersionApplication(validName,
                new SemverVersion("2.1.0"), new SemverVersion("2.2.0"));

        assertTrue(minorBehind.hasDriftAtLeast(VersionValue.Diff.PATCH));
        assertTrue(minorBehind.hasDriftAtLeast(VersionValue.Diff.MINOR));
        assertFalse(minorBehind.hasDriftAtLeast(VersionValue.Diff.MAJOR));
    }

    @Test
    void hasDriftAtLeast_majorApp_meetsEveryThreshold() {
        VersionApplication majorBehind = new VersionApplication(validName,
                new SemverVersion("1.1.1"), new SemverVersion("2.2.2"));

        assertTrue(majorBehind.hasDriftAtLeast(VersionValue.Diff.PATCH));
        assertTrue(majorBehind.hasDriftAtLeast(VersionValue.Diff.MINOR));
        assertTrue(majorBehind.hasDriftAtLeast(VersionValue.Diff.MAJOR));
    }
}
