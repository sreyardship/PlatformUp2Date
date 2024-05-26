package org.yardship.core.domain.primitives;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record Application(Version current, Version latest) {
}
