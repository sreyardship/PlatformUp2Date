package org.yardship.integration.adapters.in.http;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.scrapestate.ScrapeStateUnavailableException;
import org.yardship.adapters.out.versionsource.ChangelogTemplates;
import org.yardship.adapters.out.versionsource.configerror.ConfigError;
import org.yardship.adapters.out.versionsource.configerror.ConfigErrorScope;
import org.yardship.adapters.out.versionsource.configerror.ConfigErrors;
import org.yardship.core.domain.primitives.ChangelogTemplate;
import org.yardship.core.domain.primitives.SemverVersion;
import org.yardship.core.domain.primitives.SideObservation;
import org.yardship.core.domain.primitives.VersionApplication;
import org.yardship.core.domain.primitives.VersionScheme;
import org.yardship.core.ports.in.ApplicationVersionPort;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;

/**
 * HTTP-level tests for {@code GET /api/v1/version}. The inbound port is mocked so these
 * exercise the controller + JAX-RS mapping in isolation from Valkey.
 *
 * <p>Each side is an object {@code {version, readAt}} rather than a bare string. Top-level
 * {@code outdated} and {@code drift} are preserved. {@code readAt} is an absolute ISO instant (raw UTC, no relative math server-side; relative rendering is client-side).
 *
 * <p>One happy-path snapshot-shape test proves the JSON serialization. The fail-closed 503
 * path covers the sole transport behaviour: when the snapshot source is unavailable the
 * endpoint must NOT degrade to a 200.
 */
@QuarkusTest
class VersionControllerIT {

    @InjectMock
    ApplicationVersionPort applicationVersionPort;

    // Per-app changelog templates are threaded into ApplicationStatus.from(...) (ADR-0021).
    // Mocked here so each test controls which apps
    // carry a template, independent of the (untemplated) shared test 'platform-config'.
    @InjectMock
    ChangelogTemplates changelogTemplates;

    // Per-app config errors (ADR-0032, issue 04) are threaded into ApplicationStatus.from(...) the
    // same way changelog templates are: mocked here so each test controls which apps carry which
    // errors, independent of the (clean) shared test 'platform-config'. Unstubbed
    // configErrors.forApp(...) returns Mockito's default empty list — exactly the "clean app"
    // shape.
    @InjectMock
    ConfigErrors configErrors;

    @Test
    void getVersion_returnsPerSideObjects_withVersionAndReadAt() {
        // Use an outdated app so the single kept shape test exercises the non-trivial
        // serialization: outdated == true and a non-NONE drift label string. The full
        // per-drift-level matrix is owned by SemverVersionTests (unit).
        Instant readAt = Instant.parse("2026-07-01T10:00:00Z");
        when(applicationVersionPort.getApplications()).thenReturn(List.of(
                new VersionApplication("grafana",
                        SideObservation.resolved(new SemverVersion("2.2.0"), readAt),
                        SideObservation.resolved(new SemverVersion("2.2.1"), readAt))));

        given()
                .when()
                .get("/api/v1/version")
                .then()
                .statusCode(200)
                // Each side is a {version, readAt} object — not a bare string.
                .body("'grafana'.current.version", equalTo("2.2.0"))
                .body("'grafana'.current.readAt", notNullValue())
                .body("'grafana'.latest.version", equalTo("2.2.1"))
                .body("'grafana'.latest.readAt", notNullValue())
                // Top-level fields are preserved.
                .body("'grafana'.outdated", equalTo(true))
                .body("'grafana'.drift", equalTo("PATCH"));
    }

    @Test
    void getVersion_readAt_isAnAbsoluteInstant() {
        // readAt must be an absolute ISO-8601 instant string (e.g. "2026-07-01T10:00:00Z"),
        // not a relative string like "5m ago". Relative rendering happens client-side.
        Instant readAt = Instant.parse("2026-07-01T10:00:00Z");
        when(applicationVersionPort.getApplications()).thenReturn(List.of(
                new VersionApplication("argocd",
                        SideObservation.resolved(new SemverVersion("2.12.0"), readAt),
                        SideObservation.resolved(new SemverVersion("2.13.0"), readAt))));

        given()
                .when()
                .get("/api/v1/version")
                .then()
                .statusCode(200)
                .body("'argocd'.current.readAt", equalTo("2026-07-01T10:00:00Z"))
                .body("'argocd'.latest.readAt", equalTo("2026-07-01T10:00:00Z"));
    }

    // --- Per-side failedAt field --------------------------------------------------------------
    //
    // The wire shape gains a nullable `failedAt` on each VersionSide.
    //   - null   when the newest attempt for that side succeeded
    //   - an ISO-8601 absolute instant when the newest attempt failed (failedRefresh() == true)

    @Test
    void getVersion_failedAt_isNull_whenNewestAttemptSucceeded() {
        // A healthy side (no failure) must emit failedAt: null in the JSON.
        Instant readAt = Instant.parse("2026-07-01T10:00:00Z");
        when(applicationVersionPort.getApplications()).thenReturn(List.of(
                new VersionApplication("prometheus",
                        SideObservation.resolved(new SemverVersion("2.53.0"), readAt),
                        SideObservation.resolved(new SemverVersion("2.54.0"), readAt))));

        given()
                .when()
                .get("/api/v1/version")
                .then()
                .statusCode(200)
                .body("'prometheus'.current.failedAt", nullValue())
                .body("'prometheus'.latest.failedAt", nullValue());
    }

    @Test
    void getVersion_failedAt_isPresentAsAbsoluteInstant_whenNewestAttemptFailed() {
        // A failed-refresh side (lastFailureAt newer than lastSuccessAt) must emit
        // failedAt as a non-null ISO-8601 instant in the JSON.
        Instant successAt = Instant.parse("2026-07-01T10:00:00Z");
        Instant failureAt = Instant.parse("2026-07-01T10:05:00Z");

        // Current side is in failed-refresh state; latest side is healthy.
        SideObservation failedCurrent = new SideObservation(
                Optional.of(new SemverVersion("2.53.0")),
                Optional.of(successAt),
                Optional.of(failureAt));
        SideObservation healthyLatest = SideObservation.resolved(new SemverVersion("2.54.0"), successAt);

        when(applicationVersionPort.getApplications()).thenReturn(List.of(
                new VersionApplication("prometheus", failedCurrent, healthyLatest)));

        given()
                .when()
                .get("/api/v1/version")
                .then()
                .statusCode(200)
                // Current (failed) side: failedAt must be the failure instant as an ISO string.
                .body("'prometheus'.current.failedAt", equalTo("2026-07-01T10:05:00Z"))
                // Latest (healthy) side: failedAt must be absent/null.
                .body("'prometheus'.latest.failedAt", nullValue())
                // Old value and readAt must still be present on the failed side.
                .body("'prometheus'.current.version", equalTo("2.53.0"))
                .body("'prometheus'.current.readAt", equalTo("2026-07-01T10:00:00Z"));
    }

    // --- Unresolved apps: wire shape ------------------------------------------------------------
    //
    // An Unresolved app (at least one side has no value) must emit:
    //   - A top-level `resolution` field: "Unresolved"
    //   - `version: null` for a side with no value
    //   - `readAt: null` for a side with no lastSuccessAt
    //   - `drift` absent/null (NOT "NONE") — an Unresolved app is never "up to date"
    //   - `outdated: false` (cannot determine staleness without values)
    // A Resolved app must emit `resolution: "Resolved"` and normal drift.

    @Test
    void getVersion_unresolvedApp_emitsResolutionFieldAndNullVersion() {
        // An app where the current side never succeeded (pending, all-empty) and latest is resolved.
        Instant latestReadAt = Instant.parse("2026-07-01T10:00:00Z");
        SideObservation pendingCurrent = new SideObservation(
                Optional.empty(), Optional.empty(), Optional.empty());
        SideObservation resolvedLatest = SideObservation.resolved(new SemverVersion("1.2.0"), latestReadAt);

        when(applicationVersionPort.getApplications()).thenReturn(List.of(
                new VersionApplication("cold-app", pendingCurrent, resolvedLatest)));

        given()
                .when()
                .get("/api/v1/version")
                .then()
                .statusCode(200)
                // resolution field must be present and set to "Unresolved".
                .body("'cold-app'.resolution", equalTo("Unresolved"))
                // current side has no value → version and readAt must be null.
                .body("'cold-app'.current.version", nullValue())
                .body("'cold-app'.current.readAt", nullValue())
                // latest side has a value → must be present.
                .body("'cold-app'.latest.version", equalTo("1.2.0"))
                .body("'cold-app'.latest.readAt", notNullValue())
                // drift must be absent/null for an Unresolved app (NOT "NONE").
                .body("'cold-app'.drift", nullValue());
    }

    @Test
    void getVersion_unresolvedApp_failedSide_emitsFailedAt_andNullVersion() {
        // A side that attempted-and-failed (never succeeded) carries lastFailureAt but no value.
        // The wire shape must emit failedAt for that side AND version: null.
        Instant failureAt = Instant.parse("2026-07-01T10:05:00Z");
        Instant latestReadAt = Instant.parse("2026-07-01T10:00:00Z");

        SideObservation failedNeverSucceededCurrent = new SideObservation(
                Optional.empty(), Optional.empty(), Optional.of(failureAt));
        SideObservation resolvedLatest = SideObservation.resolved(new SemverVersion("2.0.0"), latestReadAt);

        when(applicationVersionPort.getApplications()).thenReturn(List.of(
                new VersionApplication("new-app", failedNeverSucceededCurrent, resolvedLatest)));

        given()
                .when()
                .get("/api/v1/version")
                .then()
                .statusCode(200)
                .body("'new-app'.resolution", equalTo("Unresolved"))
                // current side: version null, readAt null, failedAt present.
                .body("'new-app'.current.version", nullValue())
                .body("'new-app'.current.readAt", nullValue())
                .body("'new-app'.current.failedAt", equalTo("2026-07-01T10:05:00Z"))
                // drift must be null (not "NONE") for Unresolved.
                .body("'new-app'.drift", nullValue());
    }

    @Test
    void getVersion_unresolvedApp_driftMustNotBeNone() {
        // Critical regression guard: an Unresolved app must NEVER emit drift: "NONE".
        // "NONE" means "up to date" on the frontend — an unknown app is not up to date.
        SideObservation pending = new SideObservation(Optional.empty(), Optional.empty(), Optional.empty());
        SideObservation resolved = SideObservation.resolved(new SemverVersion("1.0.0"),
                Instant.parse("2026-07-01T10:00:00Z"));

        when(applicationVersionPort.getApplications()).thenReturn(List.of(
                new VersionApplication("unknown-app", pending, resolved)));

        given()
                .when()
                .get("/api/v1/version")
                .then()
                .statusCode(200)
                // The critical assertion: drift must not be "NONE" for an Unresolved app.
                .body("'unknown-app'.drift", not(equalTo("NONE")))
                // It must be null/absent.
                .body("'unknown-app'.drift", nullValue());
    }

    @Test
    void getVersion_resolvedApp_emitsResolutionResolved_andNormalDrift() {
        // Regression: a fully Resolved app must still emit `resolution: "Resolved"` and its drift.
        Instant readAt = Instant.parse("2026-07-01T10:00:00Z");
        when(applicationVersionPort.getApplications()).thenReturn(List.of(
                new VersionApplication("resolved-app",
                        SideObservation.resolved(new SemverVersion("1.0.0"), readAt),
                        SideObservation.resolved(new SemverVersion("2.0.0"), readAt))));

        given()
                .when()
                .get("/api/v1/version")
                .then()
                .statusCode(200)
                .body("'resolved-app'.resolution", equalTo("Resolved"))
                .body("'resolved-app'.current.version", equalTo("1.0.0"))
                .body("'resolved-app'.latest.version", equalTo("2.0.0"))
                // drift must be non-null for a Resolved app.
                .body("'resolved-app'.drift", notNullValue())
                .body("'resolved-app'.drift", equalTo("MAJOR"));
    }

    // --- Top-level nullable changelogUrl (ADR-0021) --------------------------------------------
    //
    // The core resolution logic (token substitution, zero-padding, per-scheme legality) is fully
    // covered by ChangelogTemplateTests (unit). This IT proves only what only real JSON wiring can
    // reveal: the field appears as a top-level sibling of drift, is null when no template is
    // configured, and is null when the latest side has no known version even with a template.

    @Test
    void getVersion_returnsResolvedChangelogUrl_forTemplatedApp() {
        Instant readAt = Instant.parse("2026-07-01T10:00:00Z");
        when(applicationVersionPort.getApplications()).thenReturn(List.of(
                new VersionApplication("argo-cd",
                        SideObservation.resolved(new SemverVersion("3.0.4"), readAt),
                        SideObservation.resolved(new SemverVersion("3.0.5"), readAt))));
        when(changelogTemplates.forApp("argo-cd")).thenReturn(Optional.of(
                new ChangelogTemplate(
                        "https://github.com/argoproj/argo-cd/releases/tag/v{version}",
                        VersionScheme.SEMVER,
                        Optional.empty())));

        given()
                .when()
                .get("/api/v1/version")
                .then()
                .statusCode(200)
                .body("'argo-cd'.changelogUrl",
                        equalTo("https://github.com/argoproj/argo-cd/releases/tag/v3.0.5"))
                // Sibling of drift at the top level — not nested under current/latest.
                .body("'argo-cd'.drift", notNullValue());
    }

    @Test
    void getVersion_changelogUrlIsNull_forAppWithNoTemplateConfigured() {
        // changelogTemplates.forApp(...) is unstubbed for this app name → Optional.empty()
        // (Mockito's default answer for an Optional-returning method).
        Instant readAt = Instant.parse("2026-07-01T10:00:00Z");
        when(applicationVersionPort.getApplications()).thenReturn(List.of(
                new VersionApplication("untemplated-app",
                        SideObservation.resolved(new SemverVersion("1.0.0"), readAt),
                        SideObservation.resolved(new SemverVersion("1.1.0"), readAt))));

        given()
                .when()
                .get("/api/v1/version")
                .then()
                .statusCode(200)
                .body("'untemplated-app'.changelogUrl", nullValue());
    }

    @Test
    void getVersion_changelogUrlIsNull_whenLatestSideHasNoKnownVersion_evenWithTemplateConfigured() {
        // A template is configured for this app, but the latest side never resolved — the
        // resolution has nothing to substitute {version} with, so changelogUrl must still be null.
        Instant readAt = Instant.parse("2026-07-01T10:00:00Z");
        SideObservation resolvedCurrent = SideObservation.resolved(new SemverVersion("1.0.0"), readAt);
        SideObservation pendingLatest = new SideObservation(Optional.empty(), Optional.empty(), Optional.empty());

        when(applicationVersionPort.getApplications()).thenReturn(List.of(
                new VersionApplication("cold-templated-app", resolvedCurrent, pendingLatest)));
        when(changelogTemplates.forApp("cold-templated-app")).thenReturn(Optional.of(
                new ChangelogTemplate(
                        "https://github.com/example/example/releases/tag/v{version}",
                        VersionScheme.SEMVER,
                        Optional.empty())));

        given()
                .when()
                .get("/api/v1/version")
                .then()
                .statusCode(200)
                .body("'cold-templated-app'.changelogUrl", nullValue());
    }

    // --- Per-app configErrors array (ADR-0032, issue 04) ----------------------------------------
    //
    // configErrors is projected on read from ConfigErrors.forApp(...) — never persisted in
    // Valkey, never carried through a scrape. It is a top-level array sibling of drift and
    // changelogUrl. Mapping logic (list of ConfigError -> {scope, message}) is covered cheaply by
    // ApplicationStatusTests (unit); these IT tests prove only what real JSON wiring can reveal:
    // the field is [] (not null/absent) for a clean app, and the wired controller actually reads
    // ConfigErrors rather than ignoring it.

    @Test
    void getVersion_cleanApp_emitsEmptyConfigErrorsArray_notNullNotAbsent() {
        Instant readAt = Instant.parse("2026-07-01T10:00:00Z");
        when(applicationVersionPort.getApplications()).thenReturn(List.of(
                new VersionApplication("clean-app",
                        SideObservation.resolved(new SemverVersion("1.0.0"), readAt),
                        SideObservation.resolved(new SemverVersion("1.0.0"), readAt))));
        // configErrors.forApp(...) unstubbed -> Mockito default empty list.

        given()
                .when()
                .get("/api/v1/version")
                .then()
                .statusCode(200)
                .body("'clean-app'.configErrors", notNullValue())
                .body("'clean-app'.configErrors", hasSize(0));
    }

    @Test
    void getVersion_sideScopeConfigError_appearsOnPayload_alongsideThatSidesFailedAt() {
        Instant successAt = Instant.parse("2026-07-01T10:00:00Z");
        Instant failureAt = Instant.parse("2026-07-01T10:05:00Z");
        SideObservation failedCurrent = new SideObservation(
                Optional.of(new SemverVersion("1.0.0")), Optional.of(successAt), Optional.of(failureAt));
        SideObservation healthyLatest = SideObservation.resolved(new SemverVersion("1.1.0"), successAt);

        when(applicationVersionPort.getApplications()).thenReturn(List.of(
                new VersionApplication("half-broken-app", failedCurrent, healthyLatest)));
        when(configErrors.forApp("half-broken-app")).thenReturn(List.of(
                new ConfigError("half-broken-app", ConfigErrorScope.CURRENT, "unknown current source type 'bogus'")));

        given()
                .when()
                .get("/api/v1/version")
                .then()
                .statusCode(200)
                .body("'half-broken-app'.configErrors", hasSize(1))
                .body("'half-broken-app'.configErrors[0].scope", equalTo("CURRENT"))
                .body("'half-broken-app'.configErrors[0].message", equalTo("unknown current source type 'bogus'"))
                .body("'half-broken-app'.current.failedAt", equalTo("2026-07-01T10:05:00Z"));
    }

    @Test
    void getVersion_appScopeConfigError_appearsOncePerApp_whileBothSidesAreUnresolved() {
        SideObservation pending = new SideObservation(Optional.empty(), Optional.empty(), Optional.empty());

        when(applicationVersionPort.getApplications()).thenReturn(List.of(
                new VersionApplication("calver-broken-app", pending, pending)));
        when(configErrors.forApp("calver-broken-app")).thenReturn(List.of(
                new ConfigError("calver-broken-app", ConfigErrorScope.APP, "invalid calver-format 'bogus'")));

        given()
                .when()
                .get("/api/v1/version")
                .then()
                .statusCode(200)
                // Exactly one entry: the resolver consumes the APP-scope error once and does not
                // re-report it per side.
                .body("'calver-broken-app'.configErrors", hasSize(1))
                .body("'calver-broken-app'.configErrors[0].scope", equalTo("APP"))
                .body("'calver-broken-app'.resolution", equalTo("Unresolved"))
                .body("'calver-broken-app'.current.version", nullValue())
                .body("'calver-broken-app'.latest.version", nullValue());
    }

    @Test
    void getVersion_changelogScopeConfigError_coexistsWithFullyResolvedAppAndNullChangelogUrl() {
        // The case that forces the scope model: a CHANGELOG-scope error degrades nothing about
        // the scrape. current, latest and drift are all populated normally; changelogUrl is null.
        Instant readAt = Instant.parse("2026-07-01T10:00:00Z");
        when(applicationVersionPort.getApplications()).thenReturn(List.of(
                new VersionApplication("bad-changelog-app",
                        SideObservation.resolved(new SemverVersion("1.0.0"), readAt),
                        SideObservation.resolved(new SemverVersion("2.0.0"), readAt))));
        // changelogTemplates.forApp(...) unstubbed -> Optional.empty(): the illegal template was
        // recorded as a config error, not resolved into a usable ChangelogTemplate.
        when(configErrors.forApp("bad-changelog-app")).thenReturn(List.of(
                new ConfigError("bad-changelog-app", ConfigErrorScope.CHANGELOG, "illegal changelog-url template")));

        given()
                .when()
                .get("/api/v1/version")
                .then()
                .statusCode(200)
                .body("'bad-changelog-app'.resolution", equalTo("Resolved"))
                .body("'bad-changelog-app'.current.version", equalTo("1.0.0"))
                .body("'bad-changelog-app'.latest.version", equalTo("2.0.0"))
                .body("'bad-changelog-app'.drift", equalTo("MAJOR"))
                .body("'bad-changelog-app'.changelogUrl", nullValue())
                // The snapshot for this app carries no failure state at all, yet configErrors is
                // populated — proof it is projected from ConfigErrors on read, not from anything
                // carried on the VersionApplication/snapshot (ADR-0019 stays untouched).
                .body("'bad-changelog-app'.current.failedAt", nullValue())
                .body("'bad-changelog-app'.latest.failedAt", nullValue())
                .body("'bad-changelog-app'.configErrors", hasSize(1))
                .body("'bad-changelog-app'.configErrors[0].scope", equalTo("CHANGELOG"))
                .body("'bad-changelog-app'.configErrors[0].message", equalTo("illegal changelog-url template"));
    }

    @Test
    void getVersion_returns503_whenSnapshotSourceUnavailable() {
        // Fail closed: Valkey unreachable surfaces as the port throwing; the read path
        // must NOT degrade to a 200 with stale/empty data — it returns 503.
        when(applicationVersionPort.getApplications())
                .thenThrow(new ScrapeStateUnavailableException("valkey unreachable", new RuntimeException()));

        given()
                .when()
                .get("/api/v1/version")
                .then()
                .statusCode(503);
    }
}
