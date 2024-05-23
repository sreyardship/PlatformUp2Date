package org.yardship.core.domain.primitives;

import org.yardship.core.domain.exceptions.InvalidVersionException;

import static org.yardship.core.domain.primitives.DomainValidator.notNull;

public record Version(String value) {

    public Version {
        value = notNull(value, new InvalidVersionException("Value cannot be null"));
    }

    @Override
    public String toString() {
        return value;
    }
}
