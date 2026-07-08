package org.yardship.core.domain.primitives;

import io.quarkus.runtime.annotations.RegisterForReflection;

import static org.yardship.core.domain.primitives.DomainValidator.notEmpty;
import static org.yardship.core.domain.primitives.DomainValidator.notNull;

@RegisterForReflection
public record VersionApplication(String name, SideObservation current, SideObservation latest) {

    public VersionApplication {
        notEmpty(name);
        notNull(name);
        notNull(current);
        notNull(latest);
    }

    /**
     * {@code true} when BOTH sides have a version value (the app has been successfully scraped on
     * both the current and latest sides). An Unresolved app has at least one value-less side.
     */
    public boolean isResolved() {
        return current.isResolved() && latest.isResolved();
    }

    /**
     * {@code true} when the current version is older than the latest.
     *
     * @throws IllegalStateException when {@link #isResolved()} is {@code false} — callers must
     *                               check {@code isResolved()} before calling this method.
     */
    public boolean isOld() {
        if (!isResolved()) {
            throw new IllegalStateException(
                    "Cannot determine staleness for Unresolved app '" + name + "': check isResolved() first");
        }
        return current.value().orElseThrow().isOlderThan(latest.value().orElseThrow());
    }

    /**
     * The version drift between current and latest.
     *
     * @throws IllegalStateException when {@link #isResolved()} is {@code false} — callers must
     *                               check {@code isResolved()} before calling this method.
     *                               This prevents an Unresolved app from silently reporting
     *                               {@code NONE} (which would be treated as "up to date").
     */
    public VersionValue.Diff drift() {
        if (!isResolved()) {
            throw new IllegalStateException(
                    "Cannot compute drift for Unresolved app '" + name + "': check isResolved() first");
        }
        if (!isOld()) {
            return VersionValue.Diff.NONE;
        }
        return current.value().orElseThrow().diff(latest.value().orElseThrow());
    }

    /** Transitively guarded: delegates to {@link #drift()} which throws when unresolved. */
    public boolean hasDriftAtLeast(VersionValue.Diff minimum) {
        return drift().isAtLeast(minimum);
    }

    /**
     * {@code true} when at least one side's most recent scrape attempt failed.
     *
     * <p>Formally: {@code current.failedRefresh() || latest.failedRefresh()}. A pending side
     * (never attempted) is NOT a failure — this predicate is {@code false} when both sides are
     * pending. Used by the {@code list_applications_with_failed_scrapes} MCP tool.
     */
    public boolean hasFailedScrape() {
        return current.failedRefresh() || latest.failedRefresh();
    }
}
