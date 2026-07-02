package org.yardship.adapters.in.mcp;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.yardship.core.domain.primitives.VersionApplication;
import org.yardship.core.domain.primitives.VersionValue;

import java.time.Instant;

/**
 * AI-facing projection of a monitored application's version status. This is the
 * shape returned by the MCP tools; it flattens the domain {@link VersionApplication}
 * into plain strings/booleans/instants so the MCP client (and the LLM behind it) can read it.
 *
 * <p>For Unresolved apps (at least one value-less side), {@code current}/{@code latest} may be
 * {@code null}, {@code drift} is {@code null}, and {@code resolution} is {@code "Unresolved"}.
 * Per-side read/failure instants are {@code null} when absent or when a failure did not occur
 * (respectively).
 */
@RegisterForReflection
public record ApplicationView(
        String name,
        String current,
        String latest,
        boolean outdated,
        String drift,
        String resolution,
        Instant currentReadAt,
        Instant currentFailedAt,
        Instant latestReadAt,
        Instant latestFailedAt) {

    public static ApplicationView from(VersionApplication app) {
        String current = app.current().value().map(v -> v.value()).orElse(null);
        String latest = app.latest().value().map(v -> v.value()).orElse(null);
        VersionValue.Diff drift = app.isResolved() ? app.drift() : null;
        boolean outdated = drift != null && drift != VersionValue.Diff.NONE;

        String resolution = app.isResolved() ? "Resolved" : "Unresolved";

        Instant currentReadAt = app.current().lastSuccessAt().orElse(null);
        Instant currentFailedAt = app.current().failedRefresh()
                ? app.current().lastFailureAt().orElse(null)
                : null;

        Instant latestReadAt = app.latest().lastSuccessAt().orElse(null);
        Instant latestFailedAt = app.latest().failedRefresh()
                ? app.latest().lastFailureAt().orElse(null)
                : null;

        return new ApplicationView(
                app.name(),
                current,
                latest,
                outdated,
                drift != null ? drift.name() : null,
                resolution,
                currentReadAt,
                currentFailedAt,
                latestReadAt,
                latestFailedAt);
    }
}
