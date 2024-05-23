package org.yardship.core.domain.primitives;

public class DomainValidator {

    public static <T, E extends Throwable> T notNull(T object, E ex) throws E {
        if (object == null) {
            throw ex;
        }
        return object;
    }
}
