package org.yardship.unit.core.domain.primitives;

import io.quarkus.test.junit.QuarkusTest;
import org.yardship.core.domain.exceptions.InvalidDomainObjectException;
import org.junit.jupiter.api.Test;

import static org.yardship.core.domain.primitives.DomainValidator.notEmpty;
import static org.yardship.core.domain.primitives.DomainValidator.notNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
public class DomainValidatorTests {

    @Test
    void notNull_onlyAllowsNonNullInputs() {
        // Arrange
        String validString = "Dummy string";

        // Act & Assert
        assertEquals(notNull(validString), validString);
        assertThrows(InvalidDomainObjectException.class,
                () -> notNull(null));
    }

    @Test
    void notEmpty_doesNotAllowEmptyStrings() {
        // Arrange
        String validString = "Dummy string";

        // Act & Assert
        assertEquals(notEmpty(validString), validString);
        assertThrows(InvalidDomainObjectException.class,
                () -> notEmpty(""));
    }
}
