package org.yardship.integration.adapters.in.http;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.scrapestate.ScrapeStateUnavailableException;
import org.yardship.core.domain.primitives.SemverVersion;
import org.yardship.core.domain.primitives.SideObservation;
import org.yardship.core.domain.primitives.VersionApplication;
import org.yardship.core.ports.in.ApplicationVersionPort;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;

/**
 * HTTP-level tests for {@code GET /api/v1/version}. The inbound port is mocked so these
 * exercise the controller + JAX-RS mapping in isolation from Valkey.
 *
 * <p>Slice 01 wire shape: each side is now an object {@code {version, readAt}} rather than a bare
 * string. Top-level {@code outdated} and {@code drift} are preserved. {@code readAt} is an
 * absolute ISO instant (raw UTC, no relative math server-side; relative rendering is client-side).
 *
 * <p>One happy-path snapshot-shape test proves the JSON serialization. The fail-closed 503
 * path covers the sole transport behaviour: when the snapshot source is unavailable the
 * endpoint must NOT degrade to a 200.
 */
@QuarkusTest
class VersionControllerIT {

    @InjectMock
    ApplicationVersionPort applicationVersionPort;

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

    // --- Slice 02: per-side failedAt field ----------------------------------------------------
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

    // --- Issue 03: Unresolved apps — wire shape --------------------------------------------------
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
