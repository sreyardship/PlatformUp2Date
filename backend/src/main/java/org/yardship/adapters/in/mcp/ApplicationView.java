package org.yardship.adapters.in.mcp;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.yardship.core.domain.primitives.VersionApplication;

/**
 * AI-facing projection of a monitored application's version status. This is the
 * shape returned by the MCP tools; it flattens the domain {@link VersionApplication}
 * into plain strings/booleans so the MCP client (and the LLM behind it) can read it.
 */
@RegisterForReflection
public record ApplicationView(
        String name,
        String current,
        String latest,
        boolean outdated,
        String drift) {

    public static ApplicationView from(VersionApplication app) {
        return new ApplicationView(
                app.name(),
                app.current().value(),
                app.latest().value(),
                app.isOld(),
                app.drift().name());
    }
}
