package org.yardship.adapters.in.mcp;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import org.yardship.core.domain.primitives.Version;
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

    public ApplicationMcpTools(ApplicationVersionPort applicationVersionPort) {
        this.applicationVersionPort = applicationVersionPort;
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
            Version.Diff minSeverity) {
        Version.Diff threshold = minSeverity == null ? Version.Diff.PATCH : minSeverity;
        return applicationVersionPort.getApplications().stream()
                .filter(app -> app.hasDriftAtLeast(threshold))
                .map(ApplicationView::from)
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
                .map(ApplicationView::from)
                .orElse(null);
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
}
