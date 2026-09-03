package org.yardship.adapters.in.mcp;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import org.yardship.adapters.out.versionsource.ChangelogTemplates;
import org.yardship.adapters.out.versionsource.configerror.ConfigError;
import org.yardship.adapters.out.versionsource.configerror.ConfigErrors;
import org.yardship.core.domain.primitives.ScrapeTarget;
import org.yardship.core.domain.primitives.Side;
import org.yardship.core.domain.primitives.VersionValue;
import org.yardship.core.ports.in.ScrapeStatus;
import org.yardship.core.ports.in.ApplicationVersionPort;

import java.util.List;

/**
 * MCP driving adapter. A thin projection over the inbound
 * {@link ApplicationVersionPort}: it filters/looks up monitored applications and
 * maps them into the AI-facing {@link ApplicationView}. All drift/outdated meaning
 * lives in the domain; this adapter holds no business logic of its own.
 */
@ApplicationScoped
public class ApplicationMcpTools {

    private final ApplicationVersionPort applicationVersionPort;
    private final ChangelogTemplates changelogTemplates;
    private final ConfigErrors configErrors;

    public ApplicationMcpTools(
            ApplicationVersionPort applicationVersionPort,
            ChangelogTemplates changelogTemplates,
            ConfigErrors configErrors) {
        this.applicationVersionPort = applicationVersionPort;
        this.changelogTemplates = changelogTemplates;
        this.configErrors = configErrors;
    }

    @Tool(
            name = "list_outdated_applications",
            description = "List monitored platform applications whose deployed version "
                    + "drifts behind the latest upstream release by at least the given "
                    + "severity. Use this to find what needs upgrading.")
    public List<ApplicationView> list_outdated_applications(
            @ToolArg(
                    name = "minSeverity",
                    required = false,
                    description = "Minimum drift severity to include, one of PATCH, MINOR "
                            + "or MAJOR. Omit it to return every application with any drift "
                            + "(i.e. at least a PATCH behind).")
            VersionValue.Diff minSeverity) {
        VersionValue.Diff threshold = minSeverity == null ? VersionValue.Diff.PATCH : minSeverity;
        // Guard: Unresolved apps have no drift — hasDriftAtLeast() would throw for them.
        // Exclude them here; this list is about drift, not freshness. An Unresolved or
        // failed-scrape app surfaces via get_application / list_applications_with_failed_scrapes.
        return applicationVersionPort.getApplications().stream()
                .filter(app -> app.isResolved() && app.hasDriftAtLeast(threshold))
                .map(app -> ApplicationView.from(
                        app, changelogTemplates.forApp(app.name()), configErrors.forApp(app.name())))
                .toList();
    }

    @Tool(
            name = "get_application",
            description = "Get the version status of a single monitored platform "
                    + "application by its exact name. Returns null if no application "
                    + "with that name is monitored.")
    public ApplicationView get_application(
            @ToolArg(name = "name", description = "The exact name of the application to look up.")
            String name) {
        return applicationVersionPort.getApplications().stream()
                .filter(app -> app.name().equals(name))
                .findFirst()
                .map(app -> ApplicationView.from(
                        app, changelogTemplates.forApp(app.name()), configErrors.forApp(app.name())))
                .orElse(null);
    }

    @Tool(
            name = "list_applications_with_failed_scrapes",
            description = "List monitored applications whose most recent scrape of a side FAILED "
                    + "(the current or latest read errored). This reports a failed SCRAPE/read, "
                    + "not a broken or malfunctioning application — the application itself may be "
                    + "perfectly fine. Excludes apps that are merely pending (never yet attempted). "
                    + "Use this to diagnose connectivity or configuration problems with the scrape targets.")
    public List<ApplicationView> list_applications_with_failed_scrapes() {
        return applicationVersionPort.getApplications().stream()
                .filter(app -> app.hasFailedScrape())
                .map(app -> ApplicationView.from(
                        app, changelogTemplates.forApp(app.name()), configErrors.forApp(app.name())))
                .toList();
    }

    @Tool(
            name = "list_misconfigured_applications",
            description = "List every monitored application currently affected by a recorded "
                    + "CONFIGURATION defect — an operator mistake in that app's config entry "
                    + "(an unknown source type, an uncompilable regex, a bad calver-format, an "
                    + "illegal changelog-url, an incoherent auth fragment, etc.), one entry per "
                    + "(application, scope, message). 'scope' is one of CURRENT, LATEST, APP or "
                    + "CHANGELOG and says what the defect breaks. This is NOT the same question as "
                    + "list_applications_with_failed_scrapes: that tool reports whose most recent "
                    + "READ attempt failed (a live/transient problem, ADR-0019); THIS tool reports "
                    + "whose CONFIGURATION is wrong (a static problem from the ConfigMap, discovered "
                    + "at boot). The two lists are not subsets of one another — a CHANGELOG-scope "
                    + "entry here means the app scrapes perfectly fine (both sides read normally) "
                    + "and only its changelog link is broken, so that app will never appear in "
                    + "list_applications_with_failed_scrapes. Use this tool to diagnose 'why is this "
                    + "app not configured correctly' as distinct from 'why did the last read fail'.")
    public List<ConfigErrorView> list_misconfigured_applications() {
        return configErrors.all().stream()
                .map(error -> new ConfigErrorView(
                        error.application(), error.scope().name(), error.reason()))
                .toList();
    }

    @Tool(
            name = "trigger_scrape",
            description = "Force an immediate refresh of every monitored application's version "
                    + "data, bypassing the normal staleness check. This is RATE-LIMITED: a "
                    + "rolling window caps how many manual scrapes may run. Read the returned "
                    + "'outcome' field to learn what happened: SCRAPED means a fresh scrape ran "
                    + "and the snapshot is now up to date; RATE_LIMITED means the budget was "
                    + "exhausted and NO fresh scrape happened (see 'retryAfterSeconds' for when a "
                    + "slot frees); IN_PROGRESS means another replica is already scraping, so no "
                    + "new scrape was started. After calling this tool, read "
                    + "'list_outdated_applications' or 'get_application' to see the refreshed "
                    + "data — this tool returns scrape telemetry (counts and budget), not the "
                    + "application versions themselves.")
    public ScrapeStatus trigger_scrape() {
        return applicationVersionPort.triggerScrape();
    }

    @Tool(
            name = "scrape_applications",
            description = "Force an immediate refresh of one or more specific monitored "
                    + "applications (and a chosen side of each: current, latest, or both), "
                    + "without re-hitting every upstream in the fleet. Use this when you only "
                    + "need fresh data for a handful of apps rather than a full-fleet scrape. "
                    + "This is RATE-LIMITED by a targeted scrape budget that is SEPARATE and "
                    + "distinct from trigger_scrape's fleet-wide budget. Read the returned "
                    + "'outcome' field to learn what happened: SCRAPED means the requested "
                    + "targets were refreshed and 'targetResults' holds a per-target "
                    + "success/failure outcome; RATE_LIMITED means the targeted budget was "
                    + "exhausted and NO scrape happened (see 'retryAfterSeconds' for when a "
                    + "slot frees); IN_PROGRESS means another replica is already scraping, so "
                    + "no new scrape was started. After calling this tool, read "
                    + "'get_application' or 'list_outdated_applications' to see the refreshed "
                    + "values — this tool returns scrape telemetry (counts, budget and "
                    + "per-target results), not the application versions themselves.")
    public ScrapeStatus scrape_applications(
            @ToolArg(
                    name = "targets",
                    description = "The (name, side) pairs to refresh. side is one of "
                            + "current, latest, or both.")
            List<ScrapeTargetArg> targets) {
        List<ScrapeTarget> scrapeTargets = targets.stream()
                .map(target -> new ScrapeTarget(target.name(), target.side()))
                .toList();
        return applicationVersionPort.targetedScrape(scrapeTargets);
    }

    /**
     * Wire-shape for one requested {@link ScrapeTarget} in the {@code scrape_applications} MCP
     * tool call. {@code side} binds straight to the domain {@link Side} enum, so the legal values
     * ({@code current}/{@code latest}/{@code both}) are part of the tool's argument contract and an
     * unknown value fails deserialisation rather than being parsed by hand. The
     * {@link JsonFormat.Feature#ACCEPT_CASE_INSENSITIVE_VALUES} keeps the lowercase input the tool
     * documents working against the uppercase enum constants — case-insensitivity handled by
     * Jackson, not by the adapter.
     */
    @io.quarkus.runtime.annotations.RegisterForReflection
    public record ScrapeTargetArg(
            String name,
            @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_VALUES) Side side) {
    }

    /**
     * Wire-shape for one recorded {@link ConfigError}, returned by the fleet-wide
     * {@code list_misconfigured_applications} tool (ADR-0032, issue 06). Unlike {@link
     * ApplicationView.ConfigErrorEntry} (which is nested under a single per-app payload and so
     * omits the app name), this record carries {@code application} explicitly, because this
     * listing spans the whole fleet. {@code scope} is the plain {@link
     * org.yardship.adapters.out.versionsource.configerror.ConfigErrorScope} name; {@code message}
     * is {@link ConfigError#reason()}.
     */
    @io.quarkus.runtime.annotations.RegisterForReflection
    public record ConfigErrorView(String application, String scope, String message) {
    }
}
