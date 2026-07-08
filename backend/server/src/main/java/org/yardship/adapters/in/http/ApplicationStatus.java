package org.yardship.adapters.in.http;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.yardship.core.domain.primitives.ChangelogTemplate;
import org.yardship.core.domain.primitives.SideObservation;
import org.yardship.core.domain.primitives.VersionApplication;
import org.yardship.core.domain.primitives.VersionValue;

import java.time.Instant;
import java.util.Optional;

public record ApplicationStatus(
        VersionSide current,
        VersionSide latest,
        boolean outdated,
        String drift,
        String resolution,
        String changelogUrl) {

    /**
     * Projects a {@link VersionApplication} into the wire shape.
     *
     * <p>For Unresolved apps (at least one value-less side):
     * <ul>
     *   <li>{@code resolution} = {@code "Unresolved"}</li>
     *   <li>{@code drift} = {@code null} (never {@code "NONE"} — an unknown app is not up to date)</li>
     *   <li>{@code outdated} = {@code false} (cannot determine staleness without values)</li>
     *   <li>Value-less sides emit {@code version: null} and {@code readAt: null}</li>
     * </ul>
     *
     * <p>For Resolved apps: {@code resolution} = {@code "Resolved"} and drift/outdated are computed normally.
     *
     * <p>{@code changelogUrl} (ADR-0021) is a top-level nullable field — sibling of {@code drift},
     * never nested inside a side. It is {@code null} when {@code changelogTemplate} is absent (no
     * source kind gets a default) or when the latest side has no known version to substitute.
     */
    public static ApplicationStatus from(VersionApplication app, Optional<ChangelogTemplate> changelogTemplate) {
        String resolution = app.isResolved() ? "Resolved" : "Unresolved";
        VersionValue.Diff drift = app.isResolved() ? app.drift() : null;
        boolean outdated = drift != null && drift != VersionValue.Diff.NONE;
        return new ApplicationStatus(
                toVersionSide(app.current()),
                toVersionSide(app.latest()),
                outdated,
                drift != null ? drift.name() : null,
                resolution,
                resolveChangelogUrl(changelogTemplate, app.latest()));
    }

    private static String resolveChangelogUrl(
            Optional<ChangelogTemplate> changelogTemplate, SideObservation latest) {
        return changelogTemplate
                .flatMap(template -> latest.value().map(template::resolve))
                .orElse(null);
    }

    /**
     * Maps a {@link org.yardship.core.domain.primitives.SideObservation} to the wire shape.
     * {@code version} and {@code readAt} are {@code null} when the side has no value
     * (Unresolved — never successfully read).
     */
    private static VersionSide toVersionSide(
            org.yardship.core.domain.primitives.SideObservation side) {
        String version = side.value().map(v -> v.value()).orElse(null);
        Instant readAt = side.lastSuccessAt().orElse(null);
        Instant failedAt = side.failedRefresh() ? side.lastFailureAt().orElse(null) : null;
        return new VersionSide(version, readAt, failedAt);
    }

    /**
     * Wire shape for one side (current or latest) of a monitored application.
     * {@code version} and {@code readAt} are {@code null} for a value-less (Unresolved) side.
     * {@code failedAt} is the instant of the most recent failed refresh, or {@code null}
     * when the newest attempt for this side succeeded.
     */
    @RegisterForReflection
    public record VersionSide(String version, Instant readAt, Instant failedAt) {}
}
