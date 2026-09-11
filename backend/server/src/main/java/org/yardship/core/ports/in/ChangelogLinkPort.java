package org.yardship.core.ports.in;

import org.yardship.core.domain.primitives.ChangelogTemplate;

import java.util.Optional;

/**
 * Driving port: the Changelog link one Application projects (ADR-0021, ADR-0035).
 *
 * <p>A Surface renders the link by applying the template to the Application's current version — the
 * {@code changelogUrl} field on the REST payload and on the MCP response. Empty means the
 * Application has no link: either none was configured, or the configured one was illegal and
 * collapsed to "no link" plus a Config error (ADR-0032).
 *
 * <p>Kept apart from {@link ConfigErrorPort} because Changelog link and Config error are two terms
 * in {@code CONTEXT.md}, not one.
 */
public interface ChangelogLinkPort {

    /** The Changelog link template for {@code applicationName}, or empty when it has none. */
    Optional<ChangelogTemplate> changelogFor(String applicationName);
}
