package org.yardship.core.ports.out;

import org.yardship.core.domain.primitives.ChangelogTemplate;

import java.util.Optional;

/**
 * Out-port exposing the per-app Changelog link template (ADR-0021, ADR-0035).
 *
 * <p>Built once, from the same boot-time pass over the configuration document that assembles the
 * Version sources. An Application with no {@code changelog-url} configured, and an Application
 * whose template is illegal (ADR-0032), are the same answer here: empty. The second case also
 * records a {@code CHANGELOG}-scope Config error, which is a separate read.
 */
public interface ChangelogLinks {

    /** The resolved template for {@code appName}, or empty when it has no Changelog link. */
    Optional<ChangelogTemplate> forApp(String appName);
}
