package org.yardship.core.domain.primitives;

import org.yardship.core.domain.exceptions.InvalidDomainObjectException;

public class DomainValidator {

    public static String notEmpty(String input) {
        return notEmpty(input, new InvalidDomainObjectException("Input cannot be empty"));
    }

    public static <E extends Throwable> String notEmpty(String input, E ex) throws E {
        input = notNull(input);
        if (input.isEmpty()) {
            throw ex;
        }
        return input;
    }

    public static <T> T notNull(T object) {
        return notNull(object, new InvalidDomainObjectException("Input cannot be null"));
    }

    public static <T, E extends Throwable> T notNull(T object, E ex) throws E {
        if (object == null) {
            throw ex;
        }
        return object;
    }
}
