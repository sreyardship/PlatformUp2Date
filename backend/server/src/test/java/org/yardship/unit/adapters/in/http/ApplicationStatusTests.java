package org.yardship.unit.adapters.in.http;

import org.junit.jupiter.api.Test;
import org.yardship.adapters.in.http.ApplicationStatus;
import org.yardship.adapters.out.versionsource.configerror.ConfigError;
import org.yardship.adapters.out.versionsource.configerror.ConfigErrorScope;
import org.yardship.core.domain.primitives.SemverVersion;
import org.yardship.core.domain.primitives.SideObservation;
import org.yardship.core.domain.primitives.VersionApplication;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@code configErrors} projection on {@link ApplicationStatus} (ADR-0032,
 * issue 04). {@code ApplicationStatus.from(...)} is a pure projection (no CDI, no HTTP), so these
 * exercise the mapping logic directly and cheaply.
 *
 * <p>JSON wire shape (empty array vs. {@code null}/absent field, sibling placement alongside
 * {@code drift} and {@code changelogUrl}) is proven at the integration level by {@code
 * VersionControllerIT} — that is transport/serialization, not pure logic, so it is not
 * re-asserted here (only the record's field is asserted, never raw JSON).
 */
class ApplicationStatusTests {

    private static final Instant READ_AT = Instant.parse("2026-07-01T10:00:00Z");

    @Test
    void from_cleanApp_configErrorsIsEmpty_notNull() {
        VersionApplication app = resolvedApp("grafana");

        ApplicationStatus status = ApplicationStatus.from(app, Optional.empty(), List.of());

        assertNotNull(status.configErrors());
        assertTrue(status.configErrors().isEmpty());
    }

    @Test
    void from_projectsAConfigError_asScopeAndMessage() {
        VersionApplication app = resolvedApp("argocd");
        List<ConfigError> errors = List.of(
                new ConfigError("argocd", ConfigErrorScope.CURRENT, "unknown current source type 'bogus'"));

        ApplicationStatus status = ApplicationStatus.from(app, Optional.empty(), errors);

        assertEquals(1, status.configErrors().size());
        assertEquals("CURRENT", status.configErrors().get(0).scope());
        assertEquals("unknown current source type 'bogus'", status.configErrors().get(0).message());
    }

    @Test
    void from_projectsMultipleConfigErrorsForTheSameApp_inOrder() {
        VersionApplication app = resolvedApp("multi-broken-app");
        List<ConfigError> errors = List.of(
                new ConfigError("multi-broken-app", ConfigErrorScope.CURRENT, "current is broken"),
                new ConfigError("multi-broken-app", ConfigErrorScope.CHANGELOG, "changelog is broken"));

        ApplicationStatus status = ApplicationStatus.from(app, Optional.empty(), errors);

        assertEquals(2, status.configErrors().size());
        assertEquals("CURRENT", status.configErrors().get(0).scope());
        assertEquals("current is broken", status.configErrors().get(0).message());
        assertEquals("CHANGELOG", status.configErrors().get(1).scope());
        assertEquals("changelog is broken", status.configErrors().get(1).message());
    }

    @Test
    void from_appScopeConfigError_appearsOnce_whileAppIsUnresolved() {
        // An APP-scope error (e.g. an invalid calver-format) degrades both sides — the resolver
        // consumes it once and does not re-report it on each side, so exactly one entry is
        // projected here even though both sides are unresolved as a consequence.
        SideObservation pending = new SideObservation(Optional.empty(), Optional.empty(), Optional.empty());
        VersionApplication app = new VersionApplication("calver-broken-app", pending, pending);
        List<ConfigError> errors = List.of(
                new ConfigError("calver-broken-app", ConfigErrorScope.APP, "invalid calver-format 'bogus'"));

        ApplicationStatus status = ApplicationStatus.from(app, Optional.empty(), errors);

        assertEquals(1, status.configErrors().size());
        assertEquals("APP", status.configErrors().get(0).scope());
        assertEquals("Unresolved", status.resolution());
        assertNull(status.current().version());
        assertNull(status.latest().version());
    }

    @Test
    void from_changelogScopeConfigError_coexistsWithFullyResolvedDriftAndNullChangelogUrl() {
        // The whole point of the scope model: a CHANGELOG-scope error is not a contradiction with
        // a perfectly resolved app — current, latest and drift are all populated normally, and
        // changelogUrl is null (no legal template to resolve against).
        VersionApplication app = new VersionApplication("bad-changelog-app",
                SideObservation.resolved(new SemverVersion("1.0.0"), READ_AT),
                SideObservation.resolved(new SemverVersion("2.0.0"), READ_AT));
        List<ConfigError> errors = List.of(
                new ConfigError("bad-changelog-app", ConfigErrorScope.CHANGELOG, "illegal changelog-url template"));

        ApplicationStatus status = ApplicationStatus.from(app, Optional.empty(), errors);

        assertEquals("Resolved", status.resolution());
        assertEquals("MAJOR", status.drift());
        assertEquals("1.0.0", status.current().version());
        assertEquals("2.0.0", status.latest().version());
        assertNull(status.changelogUrl());
        assertEquals(1, status.configErrors().size());
        assertEquals("CHANGELOG", status.configErrors().get(0).scope());
        assertEquals("illegal changelog-url template", status.configErrors().get(0).message());
    }

    private static VersionApplication resolvedApp(String name) {
        return new VersionApplication(name,
                SideObservation.resolved(new SemverVersion("1.0.0"), READ_AT),
                SideObservation.resolved(new SemverVersion("1.0.0"), READ_AT));
    }
}
