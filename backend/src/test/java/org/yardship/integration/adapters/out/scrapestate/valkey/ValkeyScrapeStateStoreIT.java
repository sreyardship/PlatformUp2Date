package org.yardship.integration.adapters.out.scrapestate.valkey;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.yardship.core.domain.primitives.ScrapeSnapshot;
import org.yardship.core.domain.primitives.SemverVersion;
import org.yardship.core.domain.primitives.SideObservation;
import org.yardship.core.domain.primitives.VersionValue;
import org.yardship.core.domain.primitives.VersionApplication;
import org.yardship.core.ports.out.ScrapeStateStore;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the real {@link org.yardship.adapters.out.scrapestate.valkey.ValkeyScrapeStateStore}
 * adapter against a Valkey container started by Quarkus Dev Services (the {@code quarkus-redis-client}
 * extension auto-starts one for tests — no manual {@code quarkus.redis.hosts} needed).
 *
 * <p>Verifies:
 * <ul>
 *   <li>A write→read round-trip preserves the snapshot shape (apps + lastAttemptAt).</li>
 *   <li>Per-side {@code lastSuccessAt} is round-tripped correctly (slice 01).</li>
 *   <li>A safety TTL is set on the backing key so a stuck snapshot eventually expires.</li>
 * </ul>
 */
@QuarkusTest
class ValkeyScrapeStateStoreIT {

    @Inject
    ScrapeStateStore sut;

    @Inject
    RedisDataSource redisDataSource;

    @Test
    void writeThenRead_roundTripsSnapshotShape() {
        Instant readAt = Instant.parse("2026-06-15T12:00:00Z");
        VersionApplication app = new VersionApplication(
                "argo-cd",
                SideObservation.resolved(new SemverVersion("1.0.0"), readAt),
                SideObservation.resolved(new SemverVersion("2.0.0"), readAt));
        Instant attemptAt = Instant.parse("2026-06-15T12:00:00Z");

        sut.write(List.of(app), attemptAt);

        Optional<ScrapeSnapshot> read = sut.read();

        assertTrue(read.isPresent(), "a written snapshot must be readable back");
        ScrapeSnapshot snapshot = read.get();
        assertEquals(attemptAt, snapshot.lastAttemptAt());
        assertEquals(1, snapshot.applications().size());
        VersionApplication roundTripped = snapshot.applications().getFirst();
        assertEquals("argo-cd", roundTripped.name());
        assertEquals("1.0.0", roundTripped.current().value().orElseThrow().value());
        assertEquals("2.0.0", roundTripped.latest().value().orElseThrow().value());
    }

    @Test
    void writeThenRead_roundTripsPerSideLastSuccessAt() {
        // Slice 01 contract: the per-side lastSuccessAt is persisted and rehydrated faithfully.
        // lastFailureAt must be absent on a successful read (it is populated in slice 02).
        Instant successAt = Instant.parse("2026-07-01T10:00:00Z");
        VersionApplication app = new VersionApplication(
                "grafana",
                SideObservation.resolved(new SemverVersion("10.0.0"), successAt),
                SideObservation.resolved(new SemverVersion("11.0.0"), successAt));
        Instant attemptAt = Instant.parse("2026-07-01T10:00:05Z");

        sut.write(List.of(app), attemptAt);

        Optional<ScrapeSnapshot> read = sut.read();
        assertTrue(read.isPresent());
        VersionApplication roundTripped = read.get().applications().getFirst();

        // Current side
        assertTrue(roundTripped.current().isResolved(), "current must be resolved after a successful read");
        assertEquals("10.0.0", roundTripped.current().value().orElseThrow().value());
        assertEquals(successAt, roundTripped.current().lastSuccessAt().orElseThrow(),
                "current lastSuccessAt must round-trip exactly");
        assertTrue(roundTripped.current().lastFailureAt().isEmpty(),
                "lastFailureAt must be absent in slice 01 (no failures modelled yet)");

        // Latest side
        assertTrue(roundTripped.latest().isResolved(), "latest must be resolved after a successful read");
        assertEquals("11.0.0", roundTripped.latest().value().orElseThrow().value());
        assertEquals(successAt, roundTripped.latest().lastSuccessAt().orElseThrow(),
                "latest lastSuccessAt must round-trip exactly");
        assertTrue(roundTripped.latest().lastFailureAt().isEmpty(),
                "lastFailureAt must be absent in slice 01 (no failures modelled yet)");
    }

    // --- Slice 02: lastFailureAt round-trip ---------------------------------------------------
    //
    // When a side has a prior value + lastSuccessAt AND a newer lastFailureAt (failed-refresh
    // state), the full DTO must be persisted and rehydrated faithfully — including lastFailureAt.
    // This is a pure adapter serialisation test: it does not re-test failedRefresh() logic.

    @Test
    void writeThenRead_roundTripsLastFailureAt_onFailedRefreshSide() {
        // Build a SideObservation that is in failed-refresh state: has a prior value + lastSuccessAt
        // (kept from the last good read) and a newer lastFailureAt (stamped by the scrape clock).
        Instant successAt = Instant.parse("2026-07-01T10:00:00Z");
        Instant failureAt = Instant.parse("2026-07-01T10:05:00Z"); // newer than successAt

        // Construct directly via the public ctor (the implementer will add a factory; the ctor is
        // already public and used in tests, so this is a stable seam).
        SideObservation failedCurrentSide = new SideObservation(
                Optional.of(new SemverVersion("1.0.0")),
                Optional.of(successAt),
                Optional.of(failureAt));
        // Latest side is healthy (no failure) so we can verify the null-failure case too.
        SideObservation healthyLatestSide = SideObservation.resolved(new SemverVersion("2.0.0"), successAt);

        VersionApplication app = new VersionApplication("argocd", failedCurrentSide, healthyLatestSide);
        Instant attemptAt = failureAt;

        sut.write(List.of(app), attemptAt);

        Optional<ScrapeSnapshot> read = sut.read();
        assertTrue(read.isPresent());
        VersionApplication roundTripped = read.get().applications().getFirst();

        // Current (failed) side: lastFailureAt must survive the round-trip.
        assertEquals(Optional.of(failureAt), roundTripped.current().lastFailureAt(),
                "lastFailureAt must round-trip exactly for a failed-refresh side");
        assertEquals(Optional.of(successAt), roundTripped.current().lastSuccessAt(),
                "lastSuccessAt must also round-trip (prior good read is preserved)");
        assertEquals("1.0.0", roundTripped.current().value().orElseThrow().value(),
                "prior value must also round-trip on a failed-refresh side");

        // Latest (healthy) side: lastFailureAt must be absent after a healthy round-trip.
        assertTrue(roundTripped.latest().lastFailureAt().isEmpty(),
                "lastFailureAt must be absent for a healthy side");
        assertEquals("2.0.0", roundTripped.latest().value().orElseThrow().value());
    }

    // --- Issue 03: Unresolved app (null-value side) round-trip ----------------------------------
    //
    // An Unresolved app has at least one side with no value. The DTO stores value as NULL for such
    // sides. This test verifies:
    //   - A null value side round-trips as Optional.empty() (NOT wrapped in new SemverVersion(null)).
    //   - lastSuccessAt is also null/absent for a never-succeeded side.
    //   - lastFailureAt is present when the side attempted-and-failed.
    //   - The other (resolved) side round-trips normally alongside the null-value side.

    @Test
    void writeThenRead_roundTripsUnresolvedApp_nullValueSide() {
        // Build an Unresolved app: current side attempted-and-failed (never succeeded), latest side resolved.
        Instant failureAt = Instant.parse("2026-07-01T10:05:00Z");
        Instant latestSuccessAt = Instant.parse("2026-07-01T10:00:00Z");

        // Current side: never succeeded, failed at failureAt (value-less failed side).
        SideObservation failedCurrent = new SideObservation(
                Optional.empty(),
                Optional.empty(),
                Optional.of(failureAt));
        // Latest side: normally resolved.
        SideObservation resolvedLatest = SideObservation.resolved(new SemverVersion("2.0.0"), latestSuccessAt);

        VersionApplication app = new VersionApplication("cold-app", failedCurrent, resolvedLatest);
        Instant attemptAt = failureAt;

        sut.write(List.of(app), attemptAt);

        Optional<ScrapeSnapshot> read = sut.read();
        assertTrue(read.isPresent());
        VersionApplication roundTripped = read.get().applications().getFirst();

        assertEquals("cold-app", roundTripped.name());

        // Current (failed) side: value must be absent (not null-wrapped SemverVersion).
        assertFalse(roundTripped.current().isResolved(),
                "current side must remain Unresolved after round-trip");
        assertTrue(roundTripped.current().value().isEmpty(),
                "current side value must round-trip as Optional.empty(), not Optional.of(null)");
        assertTrue(roundTripped.current().lastSuccessAt().isEmpty(),
                "current side lastSuccessAt must be absent (never succeeded)");
        assertEquals(Optional.of(failureAt), roundTripped.current().lastFailureAt(),
                "current side lastFailureAt must round-trip exactly");

        // Latest (resolved) side: must round-trip normally.
        assertTrue(roundTripped.latest().isResolved(),
                "latest side must remain Resolved after round-trip");
        assertEquals("2.0.0", roundTripped.latest().value().orElseThrow().value());
        assertEquals(latestSuccessAt, roundTripped.latest().lastSuccessAt().orElseThrow());
        assertTrue(roundTripped.latest().lastFailureAt().isEmpty());
    }

    @Test
    void writeThenRead_roundTripsFullyPendingApp_bothSidesValueless() {
        // A fully pending app (both sides never attempted, all-empty) must also round-trip cleanly.
        // This is the "first scrape ever, both sources failed" scenario.
        SideObservation pending = new SideObservation(Optional.empty(), Optional.empty(), Optional.empty());
        VersionApplication app = new VersionApplication("brand-new-app", pending, pending);
        Instant attemptAt = Instant.parse("2026-07-01T12:00:00Z");

        sut.write(List.of(app), attemptAt);

        Optional<ScrapeSnapshot> read = sut.read();
        assertTrue(read.isPresent());
        VersionApplication roundTripped = read.get().applications().getFirst();

        assertFalse(roundTripped.current().isResolved(), "current side must be Unresolved (pending) after round-trip");
        assertFalse(roundTripped.latest().isResolved(), "latest side must be Unresolved (pending) after round-trip");
        assertTrue(roundTripped.current().value().isEmpty(), "current value must be absent");
        assertTrue(roundTripped.current().lastSuccessAt().isEmpty(), "current lastSuccessAt must be absent");
        assertTrue(roundTripped.current().lastFailureAt().isEmpty(), "current lastFailureAt must be absent");
        assertTrue(roundTripped.latest().value().isEmpty(), "latest value must be absent");
        assertTrue(roundTripped.latest().lastSuccessAt().isEmpty(), "latest lastSuccessAt must be absent");
        assertTrue(roundTripped.latest().lastFailureAt().isEmpty(), "latest lastFailureAt must be absent");
    }

    @Test
    void write_setsSafetyTtlOnTheKey() {
        Instant readAt = Instant.parse("2026-06-15T12:00:00Z");
        sut.write(
                List.of(new VersionApplication("argo-cd",
                        SideObservation.resolved(new SemverVersion("1.0.0"), readAt),
                        SideObservation.resolved(new SemverVersion("2.0.0"), readAt))),
                Instant.parse("2026-06-15T12:00:00Z"));

        // A positive TTL proves the safety expiry is applied (no never-expiring snapshot).
        long ttlSeconds = redisDataSource.key().ttl("scrape:snapshot");
        assertTrue(ttlSeconds > 0, "expected a positive safety TTL on the snapshot key, got: " + ttlSeconds);
    }
}
