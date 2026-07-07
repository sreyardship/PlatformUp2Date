package org.yardship.core.domain.primitives;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.yardship.core.domain.exceptions.InvalidDomainObjectException;

import java.time.Instant;
import java.util.Optional;

/**
 * A per-side version observation: the value read from one side (current or latest),
 * when it was last read successfully, and — in later slices — when it last failed.
 *
 * <p>Invariant: if {@link #value()} is present then {@link #lastSuccessAt()} must also
 * be present. A value without a success timestamp is not a valid observation — it would
 * imply the read succeeded without recording when.
 */
@RegisterForReflection
public record SideObservation(
        Optional<VersionValue> value,
        Optional<Instant> lastSuccessAt,
        Optional<Instant> lastFailureAt) {

    public SideObservation {
        if (value.isPresent() && lastSuccessAt.isEmpty()) {
            throw new InvalidDomainObjectException(
                    "A present value requires a present lastSuccessAt (value-implies-last-success invariant)");
        }
    }

    /**
     * Factory for a fully resolved observation: both the version value and the instant
     * at which it was successfully read are present; lastFailureAt is absent (this slice
     * never records failures).
     */
    public static SideObservation resolved(VersionValue value, Instant lastSuccessAt) {
        return new SideObservation(
                Optional.of(value),
                Optional.of(lastSuccessAt),
                Optional.empty());
    }

    /** {@code true} when a version value is present (the side was successfully read). */
    public boolean isResolved() {
        return value.isPresent();
    }

    /**
     * {@code true} when the most recent event on this side was a failure.
     *
     * <p>Formally: {@link #lastFailureAt()} is present AND either {@link #lastSuccessAt()} is
     * absent OR {@code lastFailureAt} is strictly after {@code lastSuccessAt}. Ties (same instant)
     * are treated as "not failed" — success wins.
     */
    public boolean failedRefresh() {
        return lastFailureAt.isPresent() &&
               (lastSuccessAt.isEmpty() || lastFailureAt.get().isAfter(lastSuccessAt.get()));
    }
}
