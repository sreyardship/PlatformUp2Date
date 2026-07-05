package org.yardship.integration.adapters.out.scrapestate.valkey;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.yardship.core.domain.primitives.CalverFormat;
import org.yardship.core.domain.primitives.CalverVersion;
import org.yardship.core.domain.primitives.ChangelogTemplate;
import org.yardship.core.domain.primitives.ScrapeSnapshot;
import org.yardship.core.domain.primitives.SemverVersion;
import org.yardship.core.domain.primitives.SideObservation;
import org.yardship.core.domain.primitives.VersionScheme;
import org.yardship.core.domain.primitives.VersionValue;
import org.yardship.core.domain.primitives.VersionApplication;
import org.yardship.core.ports.out.ScrapeStateStore;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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

    // --- Issue 02: calver-scheme apps must round-trip as CalverVersion, not SemverVersion --------
    //
    // Today toSideObservation hardcodes `new SemverVersion(value)` regardless of which scheme
    // produced the value. This is a live production incident for the fleet's one calver app
    // (openwrt-router): the persisted value comes back as a SemverVersion, and any code path that
    // needs it to genuinely be a CalverVersion (e.g. ChangelogTemplate resolving a calver token)
    // throws ClassCastException.
    //
    // NOTE on CalverVersion.equals(): it compares CalverFormat by reference identity
    // (`format == that.format`), and CalverFormat has no equals() override. A CalverVersion
    // rehydrated from Valkey is necessarily built against a NEW CalverFormat instance
    // (reconstructed from the persisted format string), so `rehydrated.equals(original)` is
    // expected to be FALSE even though the two are semantically identical. These tests therefore
    // assert on value()/scheme()/isOlderThan()/diff() behaviour rather than CalverVersion.equals().

    // Deliberately chosen so the raw strings ALSO parse as valid semver (major.minor.patch, no
    // zero-padding). This matters for the red-phase failure mode: the pre-fix adapter blindly does
    // `new SemverVersion(value)`, and a genuinely calver-shaped string like "24.04" (2 components)
    // would fail that parse and blow up inside read() itself with InvalidVersionException — masking
    // the actual production bug, which is silent MIS-typing (a value that happens to parse as
    // semver comes back as the wrong VersionValue subtype), not a parse failure. Using
    // "2024.4.1"/"2024.5.2" reproduces the real incident: read() succeeds pre-fix (wrongly, as
    // SemverVersion) and the failure only surfaces downstream where CalverVersion-specific behaviour
    // is required — exactly like ChangelogTemplate.resolveToken casting to CalverVersion in production.
    private static final CalverFormat CALVER_FORMAT = new CalverFormat("YYYY.MM.MICRO");

    @Test
    void writeThenRead_roundTripsCalverVersion() {
        Instant successAt = Instant.parse("2026-07-01T10:00:00Z");
        CalverVersion originalCurrent = new CalverVersion("2024.4.1", CALVER_FORMAT);
        CalverVersion originalLatest = new CalverVersion("2024.5.2", CALVER_FORMAT);

        VersionApplication app = new VersionApplication(
                "openwrt-router",
                SideObservation.resolved(originalCurrent, successAt),
                SideObservation.resolved(originalLatest, successAt));
        Instant attemptAt = Instant.parse("2026-07-01T10:00:05Z");

        sut.write(List.of(app), attemptAt);

        Optional<ScrapeSnapshot> read = sut.read();
        assertTrue(read.isPresent());
        VersionApplication roundTripped = read.get().applications().getFirst();

        VersionValue rehydratedCurrent = roundTripped.current().value().orElseThrow();
        VersionValue rehydratedLatest = roundTripped.latest().value().orElseThrow();

        assertInstanceOf(CalverVersion.class, rehydratedCurrent,
                "a calver-scheme app must rehydrate as CalverVersion, not SemverVersion");
        assertInstanceOf(CalverVersion.class, rehydratedLatest,
                "a calver-scheme app must rehydrate as CalverVersion, not SemverVersion");
        assertEquals(VersionScheme.CALVER, rehydratedCurrent.scheme());
        assertEquals(VersionScheme.CALVER, rehydratedLatest.scheme());
        assertEquals("2024.4.1", rehydratedCurrent.value());
        assertEquals("2024.5.2", rehydratedLatest.value());

        // Ordering/diff behaviour must be preserved across the round-trip, not just the string value.
        assertEquals(
                originalCurrent.isOlderThan(originalLatest),
                rehydratedCurrent.isOlderThan(rehydratedLatest),
                "isOlderThan must behave the same before and after the round-trip");
        assertTrue(rehydratedCurrent.isOlderThan(rehydratedLatest),
                "2024.4.1 must remain older than 2024.5.2 after the round-trip");
        assertEquals(originalCurrent.diff(originalLatest), rehydratedCurrent.diff(rehydratedLatest),
                "diff severity must behave the same before and after the round-trip");
    }

    @Test
    void writeThenRead_calverVersion_resolvesChangelogTemplateWithoutClassCastException() {
        // Direct regression test for the production incident: resolving a ChangelogTemplate with a
        // calver-token placeholder against a value that round-tripped through Valkey must not throw
        // ClassCastException (which today takes down the whole /api/v1/version response).
        Instant successAt = Instant.parse("2026-07-01T10:00:00Z");
        CalverVersion originalLatest = new CalverVersion("2024.5.2", CALVER_FORMAT);

        VersionApplication app = new VersionApplication(
                "openwrt-router",
                SideObservation.resolved(new CalverVersion("2024.4.1", CALVER_FORMAT), successAt),
                SideObservation.resolved(originalLatest, successAt));
        Instant attemptAt = Instant.parse("2026-07-01T10:00:05Z");

        sut.write(List.of(app), attemptAt);

        Optional<ScrapeSnapshot> read = sut.read();
        assertTrue(read.isPresent());
        VersionValue rehydratedLatest = read.get().applications().getFirst().latest().value().orElseThrow();

        ChangelogTemplate template = new ChangelogTemplate(
                "https://example.com/changelog/{YYYY}.{MM}.{MICRO}", VersionScheme.CALVER,
                Optional.of(CALVER_FORMAT));

        String resolved;
        try {
            resolved = template.resolve(rehydratedLatest);
        } catch (ClassCastException e) {
            fail("resolving a calver-token ChangelogTemplate against a round-tripped value must not "
                    + "throw ClassCastException: " + e);
            return;
        }
        assertEquals("https://example.com/changelog/2024.5.2", resolved);
    }

    @Test
    void read_preFixSnapshotWithNoVersionSchemeField_defaultsToSemverVersion() {
        // Backward-compat: production Valkey right now holds a real snapshot written by the OLD
        // code (no versionScheme/calverFormat keys at all), with a 7-day safety TTL. Reading it back
        // after this fix ships must still succeed and rehydrate as SemverVersion — the exact type
        // every currently-persisted (pre-fix) snapshot already gets.
        Instant successAt = Instant.parse("2026-07-01T10:00:00Z");
        Instant attemptAt = Instant.parse("2026-07-01T10:00:05Z");

        String preFixJson = "{"
                + "\"applications\":[{"
                + "\"name\":\"argo-cd\","
                + "\"currentValue\":\"1.0.0\","
                + "\"currentLastSuccessAtEpochMillis\":" + successAt.toEpochMilli() + ","
                + "\"currentLastFailureAtEpochMillis\":null,"
                + "\"latestValue\":\"2.0.0\","
                + "\"latestLastSuccessAtEpochMillis\":" + successAt.toEpochMilli() + ","
                + "\"latestLastFailureAtEpochMillis\":null"
                + "}],"
                + "\"lastAttemptAtEpochMillis\":" + attemptAt.toEpochMilli()
                + "}";

        redisDataSource.value(String.class, String.class).set("scrape:snapshot", preFixJson);

        Optional<ScrapeSnapshot> read = sut.read();

        assertTrue(read.isPresent(), "a pre-fix (no versionScheme field) snapshot must still be readable");
        VersionApplication roundTripped = read.get().applications().getFirst();
        assertEquals("argo-cd", roundTripped.name());

        VersionValue current = roundTripped.current().value().orElseThrow();
        VersionValue latest = roundTripped.latest().value().orElseThrow();
        assertInstanceOf(SemverVersion.class, current,
                "a pre-fix snapshot with no versionScheme field must default to SemverVersion");
        assertInstanceOf(SemverVersion.class, latest,
                "a pre-fix snapshot with no versionScheme field must default to SemverVersion");
        assertEquals("1.0.0", current.value());
        assertEquals("2.0.0", latest.value());
    }
}
