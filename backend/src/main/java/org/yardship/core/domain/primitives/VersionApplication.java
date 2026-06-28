package org.yardship.core.domain.primitives;

import io.quarkus.runtime.annotations.RegisterForReflection;

import static org.yardship.core.domain.primitives.DomainValidator.notEmpty;
import static org.yardship.core.domain.primitives.DomainValidator.notNull;

@RegisterForReflection
public record VersionApplication(String name, VersionValue current, VersionValue latest) {

    public VersionApplication {
        notEmpty(name);
        notNull(name);
        notNull(current);
        notNull(latest);
    }

    public boolean isOld() {
        return current.isOlderThan(latest);
    }

    public VersionValue.Diff drift() {
        if (!isOld()) {
            return VersionValue.Diff.NONE;
        }
        return current.diff(latest);
    }

    public boolean hasDriftAtLeast(VersionValue.Diff minimum) {
        return drift().isAtLeast(minimum);
    }
}
