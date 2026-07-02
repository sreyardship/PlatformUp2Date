package org.yardship.unit.core.domain.primitives;

import org.junit.jupiter.api.Test;
import org.yardship.core.domain.exceptions.InvalidDomainObjectException;
import org.yardship.core.domain.primitives.SemverVersion;
import org.yardship.core.domain.primitives.SideObservation;
import org.yardship.core.domain.primitives.VersionValue;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SideObservation}: the per-side version observation value object
 * introduced in slice 01.
 *
 * <p>Pins down:
 * <ul>
 *   <li>The invariant that a present value implies a present lastSuccessAt.</li>
 *   <li>{@link SideObservation#isResolved()} when value is present vs absent.</li>
 *   <li>The resolved factory and its field layout.</li>
 * </ul>
 *
 * <p>No framework required — pure domain logic.
 */
class SideObservationTests {

    private static final Instant FIXED_NOW = Instant.parse("2026-07-01T10:00:00Z");
    private static final VersionValue VERSION = new SemverVersion("1.0.0");

    @Test
    void resolved_isResolved() {
        SideObservation obs = SideObservation.resolved(VERSION, FIXED_NOW);

        assertTrue(obs.isResolved(), "an observation with a value present must be resolved");
    }

    @Test
    void resolved_exposesValueAndLastSuccessAt() {
        SideObservation obs = SideObservation.resolved(VERSION, FIXED_NOW);

        assertEquals(Optional.of(VERSION), obs.value(), "resolved value must be present");
        assertEquals(Optional.of(FIXED_NOW), obs.lastSuccessAt(), "lastSuccessAt must match the supplied instant");
    }

    @Test
    void resolved_lastFailureAt_isEmpty_thisSlice() {
        SideObservation obs = SideObservation.resolved(VERSION, FIXED_NOW);

        assertTrue(obs.lastFailureAt().isEmpty(),
                "slice 01 never sets lastFailureAt on a successful read; that arrives in slice 02");
    }

    @Test
    void invariant_valuePresentRequiresLastSuccessAt() {
        // Constructing with a value but no lastSuccessAt violates the observation invariant.
        assertThrows(InvalidDomainObjectException.class,
                () -> new SideObservation(Optional.of(VERSION), Optional.empty(), Optional.empty()),
                "a value without a lastSuccessAt violates the per-side observation invariant");
    }

    @Test
    void emptyObservation_isNotResolved() {
        SideObservation obs = new SideObservation(Optional.empty(), Optional.empty(), Optional.empty());

        assertFalse(obs.isResolved(), "an observation without a value is not resolved");
    }

    @Test
    void emptyObservation_allFieldsEmpty() {
        SideObservation obs = new SideObservation(Optional.empty(), Optional.empty(), Optional.empty());

        assertTrue(obs.value().isEmpty());
        assertTrue(obs.lastSuccessAt().isEmpty());
        assertTrue(obs.lastFailureAt().isEmpty());
    }

    // --- Slice 02: failedRefresh() truth table ------------------------------------------------
    //
    // failedRefresh() == true when the most recent event on this side was a failure.
    // Formally: lastFailureAt is present AND (lastSuccessAt is absent OR lastFailureAt > lastSuccessAt).
    //
    // Cases:
    //   fresh            — success read, no failure     → false
    //   failed-refresh   — failure AFTER a prior success → true
    //   never-succeeded  — failure, no success yet       → true
    //   pending          — no success AND no failure     → false
    //   success-newer    — success AFTER a prior failure → false

    @Test
    void failedRefresh_freshObservation_successNoFailure_returnsFalse() {
        // A side that was successfully read and has never failed is NOT in a failed-refresh state.
        SideObservation obs = SideObservation.resolved(VERSION, FIXED_NOW);

        assertFalse(obs.failedRefresh(),
                "a successfully read side with no failure stamp must not report failedRefresh");
    }

    @Test
    void failedRefresh_failureNewerThanSuccess_returnsTrue() {
        // The most recent attempt failed (lastFailureAt > lastSuccessAt) — the side carries a prior
        // value + lastSuccessAt from the last good read, but the newest event was a failure.
        Instant successAt  = Instant.parse("2026-07-01T10:00:00Z");
        Instant failureAt  = Instant.parse("2026-07-01T10:05:00Z"); // 5 minutes later

        SideObservation obs = new SideObservation(
                Optional.of(VERSION),
                Optional.of(successAt),
                Optional.of(failureAt));

        assertTrue(obs.failedRefresh(),
                "when lastFailureAt is present and newer than lastSuccessAt, failedRefresh must be true");
    }

    @Test
    void failedRefresh_neverSucceeded_failurePresent_returnsTrue() {
        // The side has never had a successful read (no value, no lastSuccessAt), but a failure was
        // recorded. This slice keeps existing value — the no-value case is handled in issue 03, but
        // the truth table must still cover it here via the SideObservation ctor (no value → no lastSuccessAt).
        Instant failureAt = Instant.parse("2026-07-01T10:05:00Z");

        SideObservation obs = new SideObservation(
                Optional.empty(),
                Optional.empty(),
                Optional.of(failureAt));

        assertTrue(obs.failedRefresh(),
                "a side that has never succeeded but has a failure stamp must report failedRefresh");
    }

    @Test
    void failedRefresh_noSuccessAndNoFailure_pending_returnsFalse() {
        // Nothing has happened yet on this side.
        SideObservation obs = new SideObservation(Optional.empty(), Optional.empty(), Optional.empty());

        assertFalse(obs.failedRefresh(),
                "a side with no success and no failure (pending) must not report failedRefresh");
    }

    @Test
    void failedRefresh_successNewerThanFailure_returnsFalse() {
        // A failure happened, then a subsequent read succeeded — the failure is stale.
        Instant failureAt = Instant.parse("2026-07-01T09:55:00Z");
        Instant successAt = Instant.parse("2026-07-01T10:00:00Z"); // success came after

        SideObservation obs = new SideObservation(
                Optional.of(VERSION),
                Optional.of(successAt),
                Optional.of(failureAt));

        assertFalse(obs.failedRefresh(),
                "when lastSuccessAt is newer than lastFailureAt the refresh did NOT fail; must return false");
    }

    @Test
    void failedRefresh_successAndFailureAtSameInstant_returnsFalse() {
        // Edge case: exactly equal timestamps are treated as "not failed" (success wins ties).
        Instant sameInstant = Instant.parse("2026-07-01T10:00:00Z");

        SideObservation obs = new SideObservation(
                Optional.of(VERSION),
                Optional.of(sameInstant),
                Optional.of(sameInstant));

        assertFalse(obs.failedRefresh(),
                "when lastFailureAt == lastSuccessAt (tie) the refresh is not considered failed");
    }
}
