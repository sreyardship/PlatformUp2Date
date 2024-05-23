package org.yardship.unit.domain.primitives;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.yardship.core.domain.exceptions.InvalidVersionException;
import org.yardship.core.domain.primitives.Version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
public class VersionTests {

    @Test
    void Version_throwsInvalidVersionException_whenInputIsNull() {
        assertThrows(InvalidVersionException.class,
                () -> new Version(null));
    }

    @Test
    void toString_onlyYieldsValue() {
        String dummyVersion = "Dummy";
        String versionString = new Version(dummyVersion).toString();

        assertEquals(dummyVersion, versionString);
    }
}
