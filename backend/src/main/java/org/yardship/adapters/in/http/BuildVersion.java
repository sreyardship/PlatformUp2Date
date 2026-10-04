package org.yardship.adapters.in.http;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record BuildVersion(String version) {}
