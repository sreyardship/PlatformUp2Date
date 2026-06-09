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

    private static List<String> invalidInputs() {
        return List.of(
                "Version",
                "<div>Bad Version</div>",
                "; DROP TABLES"
        );
    }
}
