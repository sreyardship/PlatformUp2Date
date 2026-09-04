package org.yardship.adapters.in.http;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.yardship.adapters.out.versionsource.configerror.ConfigError;
import org.yardship.core.domain.primitives.ChangelogTemplate;
import org.yardship.core.domain.primitives.SideObservation;
import org.yardship.core.domain.primitives.VersionApplication;
import org.yardship.core.domain.primitives.VersionValue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public record ApplicationStatus(
        VersionSide current,
        VersionSide latest,
        boolean outdated,
        String drift,
        String resolution,
        String changelogUrl,
        List<ConfigErrorEntry> configErrors) {

    /**
     * Normalises {@code configErrors} so the "empty array, never null" contract is structural
     * rather than a promise every caller has to keep: a consumer must never have to tell "no
     * errors" apart from "field missing" (issue 04 / ADR-0032).
     */
    public ApplicationStatus {
        configErrors = configErrors == null ? List.of() : List.copyOf(configErrors);
    }

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
     *
     * <p>{@code configErrors} (ADR-0032, issue 04) is a top-level array sibling of {@code drift}
     * and {@code changelogUrl}, projected on read from {@code ConfigErrors.forApp(...)} —
     * never persisted in Valkey, never carried through a scrape. Every replica loads the same
     * config and computes the same answer, exactly like {@code changelogUrl} (ADR-0021). It is an
     * empty list, never {@code null}, for a clean app. A {@code CHANGELOG}-scope entry can appear
     * on an app whose {@code current}/{@code latest}/{@code drift} are all populated normally —
     * that combination is not a contradiction, it is the point of the scope model.
     */
    public static ApplicationStatus from(
            VersionApplication app,
            Optional<ChangelogTemplate> changelogTemplate,
            List<ConfigError> configErrors) {
        String resolution = app.isResolved() ? "Resolved" : "Unresolved";
        VersionValue.Diff drift = app.isResolved() ? app.drift() : null;
        boolean outdated = drift != null && drift != VersionValue.Diff.NONE;
        return new ApplicationStatus(
                toVersionSide(app.current()),
                toVersionSide(app.latest()),
                outdated,
                drift != null ? drift.name() : null,
                resolution,
                resolveChangelogUrl(changelogTemplate, app.latest()),
                toConfigErrorEntries(configErrors));
    }

    /**
     * Projects each {@link ConfigError} into a {@link ConfigErrorEntry} ({@code scope.name()},
     * {@code reason()}), preserving input order.
     */
    private static List<ConfigErrorEntry> toConfigErrorEntries(List<ConfigError> configErrors) {
        return configErrors.stream()
                .map(error -> new ConfigErrorEntry(error.scope().name(), error.reason()))
                .toList();
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

    /**
     * Wire shape for one recorded {@link ConfigError} (ADR-0032, issue 04). The app name is not
     * repeated here — the enclosing {@link ApplicationStatus} is already keyed by app in the
     * {@code GET /api/v1/version} payload. {@code scope} is the {@link
     * org.yardship.adapters.out.versionsource.configerror.ConfigErrorScope} name (e.g.
     * {@code "CURRENT"}, {@code "APP"}, {@code "CHANGELOG"}); {@code message} is {@link
     * ConfigError#reason()}.
     */
    // The array type is registered alongside the record: this field is a List, and Jackson
    // instantiates ConfigErrorEntry[] reflectively to build it, which native-image cannot do
    // unless the array class is registered too. Registering only the record compiles and passes
    // every JVM test, then fails at runtime in native with MissingReflectionRegistrationError.
    @RegisterForReflection(targets = { ConfigErrorEntry.class, ConfigErrorEntry[].class })
    public record ConfigErrorEntry(String scope, String message) {}
}
