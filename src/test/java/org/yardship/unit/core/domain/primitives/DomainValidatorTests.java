package org.yardship.unit.core.domain.primitives;

import io.quarkus.test.junit.QuarkusTest;
import org.yardship.core.domain.exceptions.InvalidDomainObjectException;
import org.junit.jupiter.api.Test;

import static org.yardship.core.domain.primitives.DomainValidator.notNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
public class DomainValidatorTests {

    @Test
    void notNull_returnsObject_whenObjectIsNotNull() {
        String input = "Dummy string";
        String output = notNull(input, new InvalidDomainObjectException(""));

        assertEquals(input, output);
    }

    @Test
    void notNull_throwsException_whenInputIsNull() {
        assertThrows(
            InvalidDomainObjectException.class,
            () -> notNull(null, new InvalidDomainObjectException("")));
    }
}
