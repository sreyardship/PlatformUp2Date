package org.yardship.adapters.in.mcp;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.yardship.adapters.out.versionsource.configerror.ConfigError;
import org.yardship.core.domain.primitives.ChangelogTemplate;
import org.yardship.core.domain.primitives.SideObservation;
import org.yardship.core.domain.primitives.VersionApplication;
import org.yardship.core.domain.primitives.VersionValue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * AI-facing projection of a monitored application's version status. This is the
 * shape returned by the MCP tools; it flattens the domain {@link VersionApplication}
 * into plain strings/booleans/instants so the MCP client (and the LLM behind it) can read it.
 *
 * <p>For Unresolved apps (at least one value-less side), {@code current}/{@code latest} may be
 * {@code null}, {@code drift} is {@code null}, and {@code resolution} is {@code "Unresolved"}.
 * Per-side read/failure instants are {@code null} when absent or when a failure did not occur
 * (respectively).
 *
 * <p>{@code changelogUrl} (ADR-0021) mirrors {@code ApplicationStatus}'s (the REST
 * sibling) exact semantics: {@code null} when {@code changelogTemplate} is absent, or when the
 * latest side has no known version to substitute.
 *
 * <p>{@code configErrors} (ADR-0032, issue 06) mirrors {@code ApplicationStatus}'s {@code
 * configErrors} semantics exactly: a top-level array sibling of {@code drift} and {@code
 * changelogUrl}, projected on read from {@code ConfigErrors.forApp(...)} — never persisted, never
 * carried through a scrape. Empty list, never {@code null}, for a clean app. The two wire records
 * are deliberate siblings and must stay in step.
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
        Instant latestFailedAt,
        String changelogUrl,
        List<ConfigErrorEntry> configErrors) {

    /**
     * Normalises {@code configErrors} so "empty array, never null" is structural — mirrors
     * {@code ApplicationStatus}'s compact constructor exactly.
     */
    public ApplicationView {
        configErrors = configErrors == null ? List.of() : List.copyOf(configErrors);
    }

    public static ApplicationView from(
            VersionApplication app,
            Optional<ChangelogTemplate> changelogTemplate,
            List<ConfigError> configErrors) {
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
                latestFailedAt,
                resolveChangelogUrl(changelogTemplate, app.latest()),
                toConfigErrorEntries(configErrors));
    }

    /**
     * Projects each {@link ConfigError} into a {@link ConfigErrorEntry} ({@code scope.name()},
     * {@code reason()}), preserving input order — mirrors {@code
     * ApplicationStatus.toConfigErrorEntries} exactly.
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
     * Wire shape for one recorded {@link ConfigError} (ADR-0032, issue 06). Deliberate sibling of
     * {@code ApplicationStatus.ConfigErrorEntry}: the app name is not repeated here — the enclosing
     * {@link ApplicationView} is already keyed by app. {@code scope} is the {@link
     * org.yardship.adapters.out.versionsource.configerror.ConfigErrorScope} name (e.g.
     * {@code "CURRENT"}, {@code "APP"}, {@code "CHANGELOG"}); {@code message} is {@link
     * ConfigError#reason()}.
     */
    // Array registered alongside the record — see ApplicationStatus.ConfigErrorEntry for why a
    // List-valued field needs both in native mode.
    @RegisterForReflection(targets = { ConfigErrorEntry.class, ConfigErrorEntry[].class })
    public record ConfigErrorEntry(String scope, String message) {}
}
