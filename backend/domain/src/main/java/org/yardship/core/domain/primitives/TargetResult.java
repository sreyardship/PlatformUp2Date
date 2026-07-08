package org.yardship.core.domain.primitives;

import io.quarkus.runtime.annotations.RegisterForReflection;

import static org.yardship.core.domain.primitives.DomainValidator.notEmpty;
import static org.yardship.core.domain.primitives.DomainValidator.notNull;

/**
 * The per-target outcome of one {@link ScrapeTarget} inside a targeted scrape.
 *
 * <p>{@code side} echoes the side that was actually read — equal to the requested
 * {@link ScrapeTarget#side()} except for the cold-start case (a single-side target for an app not
 * yet in the snapshot), where the service upgrades to {@code BOTH} because half a
 * {@link VersionApplication} cannot be persisted.
 *
 * <p>{@code succeeded}/{@code reason} isolate failure to this target: a source throwing, or the app
 * naming an unmonitored target ({@code reason == "not monitored"}), fails only this entry — it does
 * not sink the rest of the batch. {@code reason} is empty on success.
 */
@RegisterForReflection
public record TargetResult(String name, Side side, boolean succeeded, String reason) {

    public TargetResult {
        notEmpty(name);
        notNull(side);
        notNull(reason);
    }

    public static TargetResult success(String name, Side side) {
        return new TargetResult(name, side, true, "");
    }

    public static TargetResult failure(String name, Side side, String reason) {
        return new TargetResult(name, side, false, reason);
    }
}
