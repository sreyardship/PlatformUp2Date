package org.yardship.unit.core.domain.primitives;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.yardship.core.domain.exceptions.InvalidDomainObjectException;
import org.yardship.core.domain.primitives.Version;
import org.yardship.core.domain.primitives.VersionApplication;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class VersionApplicationTests {

    private final String validName = "Dummy Application";
    private final Version currentVersion = new Version("1.2.3");
    private final Version latestVersion = new Version("3.2.1");

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
}
