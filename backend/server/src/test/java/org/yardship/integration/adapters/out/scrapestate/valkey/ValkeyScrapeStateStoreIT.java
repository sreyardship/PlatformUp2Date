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
 * <p>Fixtures use the two apps configured in {@code src/test/resources/application.properties}:
 * {@code test-app} (semver, defaults) and {@code test-calver-app} (calver, format
 * {@code YYYY.MM.MICRO}) — see {@link org.yardship.adapters.out.versionsource.VersionParsers}. Since
 * issue 02, {@code ValkeyScrapeStateStore} retypes every stored value using the app's CONFIGURED
 * parser rather than any scheme information persisted in the snapshot (ADR-0022), so every fixture
 * app name here must be one of these two configured names.
 *
 * <p>Verifies:
 * <ul>
 *   <li>A write→read round-trip preserves the snapshot shape (apps + lastAttemptAt).</li>
 *   <li>Per-side {@code lastSuccessAt} is round-tripped correctly (slice 01).</li>
 *   <li>A safety TTL is set on the backing key so a stuck snapshot eventually expires.</li>
 *   <li>The persisted JSON is bare strings + timestamps only — no {@code versionScheme}/
 *       {@code calverFormat} fields (issue 02 / ADR-0022).</li>
 *   <li>Rehydration always follows the app's CONFIGURED scheme, ignoring any (legacy or
 *       deliberately lying) scheme fields present in the stored JSON.</li>
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
                "test-app",
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
        assertEquals("test-app", roundTripped.name());
        assertEquals("1.0.0", roundTripped.current().value().orElseThrow().value());
        assertEquals("2.0.0", roundTripped.latest().value().orElseThrow().value());
    }

    @Test
    void writeThenRead_roundTripsPerSideLastSuccessAt() {
        // Slice 01 contract: the per-side lastSuccessAt is persisted and rehydrated faithfully.
        // lastFailureAt must be absent on a successful read (it is populated in slice 02).
        Instant successAt = Instant.parse("2026-07-01T10:00:00Z");
        VersionApplication app = new VersionApplication(
                "test-app",
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

        VersionApplication app = new VersionApplication("test-app", failedCurrentSide, healthyLatestSide);
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

        VersionApplication app = new VersionApplication("test-app", failedCurrent, resolvedLatest);
        Instant attemptAt = failureAt;

        sut.write(List.of(app), attemptAt);

        Optional<ScrapeSnapshot> read = sut.read();
        assertTrue(read.isPresent());
        VersionApplication roundTripped = read.get().applications().getFirst();

        assertEquals("test-app", roundTripped.name());

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
        VersionApplication app = new VersionApplication("test-app", pending, pending);
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
                List.of(new VersionApplication("test-app",
                        SideObservation.resolved(new SemverVersion("1.0.0"), readAt),
                        SideObservation.resolved(new SemverVersion("2.0.0"), readAt))),
                Instant.parse("2026-06-15T12:00:00Z"));

        // A positive TTL proves the safety expiry is applied (no never-expiring snapshot).
        long ttlSeconds = redisDataSource.key().ttl("scrape:snapshot");
        assertTrue(ttlSeconds > 0, "expected a positive safety TTL on the snapshot key, got: " + ttlSeconds);
    }

    // --- Issue 02: the snapshot is observed strings + timestamps, nothing else (ADR-0022) --------
    //
    // The DTO no longer carries versionScheme/calverFormat fields at all: retyping happens on read,
    // driven entirely by the app's CONFIGURED parser (VersionParsers.forApp), never by anything
    // persisted in Valkey.

    @Test
    void write_persistsNoVersionSchemeOrCalverFormatFields() {
        Instant successAt = Instant.parse("2026-07-01T10:00:00Z");
        VersionApplication semverApp = new VersionApplication(
                "test-app",
                SideObservation.resolved(new SemverVersion("1.0.0"), successAt),
                SideObservation.resolved(new SemverVersion("2.0.0"), successAt));
        VersionApplication calverApp = new VersionApplication(
                "test-calver-app",
                SideObservation.resolved(new CalverVersion("2024.4.1", CALVER_FORMAT), successAt),
                SideObservation.resolved(new CalverVersion("2024.5.2", CALVER_FORMAT), successAt));

        sut.write(List.of(semverApp, calverApp), successAt);

        String rawJson = redisDataSource.value(String.class, String.class).get("scrape:snapshot");
        assertTrue(rawJson != null && !rawJson.isBlank(), "expected a snapshot to have been written");
        assertFalse(rawJson.contains("versionScheme"),
                "the written snapshot must contain no versionScheme field (ADR-0022): " + rawJson);
        assertFalse(rawJson.contains("calverFormat"),
                "the written snapshot must contain no calverFormat field (ADR-0022): " + rawJson);
    }

    // --- Issue 02: calver-scheme apps must round-trip as CalverVersion, not SemverVersion --------
    //
    // Retyping on read is driven entirely by the app's CONFIGURED scheme (test-calver-app is
    // configured as calver/YYYY.MM.MICRO in src/test/resources/application.properties), not by
    // anything persisted alongside the value. This is a live production incident for the fleet's
    // one calver app (openwrt-router): the persisted value used to come back as a SemverVersion,
    // and any code path that needs it to genuinely be a CalverVersion (e.g. ChangelogTemplate
    // resolving a calver token) throws ClassCastException.
    //
    // NOTE on CalverVersion.equals(): it compares CalverFormat by reference identity
    // (`format == that.format`), and CalverFormat has no equals() override. A CalverVersion
    // rehydrated from Valkey is necessarily built against a NEW CalverFormat instance
    // (reconstructed from config, not from anything persisted), so `rehydrated.equals(original)` is
    // expected to be FALSE even though the two are semantically identical. These tests therefore
    // assert on value()/scheme()/isOlderThan()/diff() behaviour rather than CalverVersion.equals().

    // Deliberately chosen so the raw strings ALSO parse as valid semver (major.minor.patch, no
    // zero-padding). This matters for the red-phase failure mode: the pre-fix adapter blindly does
    // `new SemverVersion(value)` for any app with no persisted versionScheme field, and a genuinely
    // calver-shaped string like "24.04" (2 components) would fail that parse and blow up inside
    // read() itself with InvalidVersionException — masking the actual production bug, which is
    // silent MIS-typing (a value that happens to parse as semver comes back as the wrong
    // VersionValue subtype), not a parse failure. Using "2024.4.1"/"2024.5.2" reproduces the real
    // incident: read() succeeds pre-fix (wrongly, as SemverVersion) and the failure only surfaces
    // downstream where CalverVersion-specific behaviour is required — exactly like
    // ChangelogTemplate.resolveToken casting to CalverVersion in production.
    private static final CalverFormat CALVER_FORMAT = new CalverFormat("YYYY.MM.MICRO");

    @Test
    void writeThenRead_roundTripsCalverVersion() {
        Instant successAt = Instant.parse("2026-07-01T10:00:00Z");
        CalverVersion originalCurrent = new CalverVersion("2024.4.1", CALVER_FORMAT);
        CalverVersion originalLatest = new CalverVersion("2024.5.2", CALVER_FORMAT);

        VersionApplication app = new VersionApplication(
                "test-calver-app",
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
    void writeThenRead_semverApp_rehydratesAsSemverVersion() {
        // Config-driven counterpart to the calver test above: test-app is configured as semver
        // (defaults), so its values must always rehydrate as SemverVersion.
        Instant successAt = Instant.parse("2026-07-01T10:00:00Z");
        VersionApplication app = new VersionApplication(
                "test-app",
                SideObservation.resolved(new SemverVersion("1.2.3"), successAt),
                SideObservation.resolved(new SemverVersion("1.3.0"), successAt));
        Instant attemptAt = Instant.parse("2026-07-01T10:00:05Z");

        sut.write(List.of(app), attemptAt);

        Optional<ScrapeSnapshot> read = sut.read();
        assertTrue(read.isPresent());
        VersionApplication roundTripped = read.get().applications().getFirst();

        VersionValue rehydratedCurrent = roundTripped.current().value().orElseThrow();
        VersionValue rehydratedLatest = roundTripped.latest().value().orElseThrow();

        assertInstanceOf(SemverVersion.class, rehydratedCurrent,
                "a semver-scheme app must rehydrate as SemverVersion");
        assertInstanceOf(SemverVersion.class, rehydratedLatest,
                "a semver-scheme app must rehydrate as SemverVersion");
        assertEquals(VersionScheme.SEMVER, rehydratedCurrent.scheme());
        assertEquals(VersionScheme.SEMVER, rehydratedLatest.scheme());
    }

    @Test
    void writeThenRead_calverVersion_resolvesChangelogTemplateWithoutClassCastException() {
        // Direct regression test for the production incident: resolving a ChangelogTemplate with a
        // calver-token placeholder against a value that round-tripped through Valkey must not throw
        // ClassCastException (which today takes down the whole /api/v1/version response).
        Instant successAt = Instant.parse("2026-07-01T10:00:00Z");
        CalverVersion originalLatest = new CalverVersion("2024.5.2", CALVER_FORMAT);

        VersionApplication app = new VersionApplication(
                "test-calver-app",
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

    // --- Issue 02: legacy snapshots (still carrying the old scheme fields) must read fine, and the
    // fields must have NO EFFECT on the result — rehydration always follows config, never the
    // persisted fields (ADR-0022). Backward compatibility is free: Quarkus's ObjectMapper ignores
    // unknown JSON properties, so these old fields simply get dropped on deserialisation.

    @Test
    void read_legacySnapshotWithNoSchemeFieldsAtAll_rehydratesFromConfig() {
        // Oldest possible shape: no versionScheme/calverFormat keys at all (pre-issue-01 snapshot).
        // For a semver-configured app (test-app) this must still rehydrate as SemverVersion.
        Instant successAt = Instant.parse("2026-07-01T10:00:00Z");
        Instant attemptAt = Instant.parse("2026-07-01T10:00:05Z");

        String legacyJson = "{"
                + "\"applications\":[{"
                + "\"name\":\"test-app\","
                + "\"currentValue\":\"1.0.0\","
                + "\"currentLastSuccessAtEpochMillis\":" + successAt.toEpochMilli() + ","
                + "\"currentLastFailureAtEpochMillis\":null,"
                + "\"latestValue\":\"2.0.0\","
                + "\"latestLastSuccessAtEpochMillis\":" + successAt.toEpochMilli() + ","
                + "\"latestLastFailureAtEpochMillis\":null"
                + "}],"
                + "\"lastAttemptAtEpochMillis\":" + attemptAt.toEpochMilli()
                + "}";

        redisDataSource.value(String.class, String.class).set("scrape:snapshot", legacyJson);

        Optional<ScrapeSnapshot> read = sut.read();

        assertTrue(read.isPresent(), "a legacy (no versionScheme field) snapshot must still be readable");
        VersionApplication roundTripped = read.get().applications().getFirst();
        assertEquals("test-app", roundTripped.name());

        VersionValue current = roundTripped.current().value().orElseThrow();
        VersionValue latest = roundTripped.latest().value().orElseThrow();
        assertInstanceOf(SemverVersion.class, current,
                "a legacy snapshot with no versionScheme field must rehydrate per the app's CONFIGURED scheme");
        assertInstanceOf(SemverVersion.class, latest,
                "a legacy snapshot with no versionScheme field must rehydrate per the app's CONFIGURED scheme");
        assertEquals("1.0.0", current.value());
        assertEquals("2.0.0", latest.value());
    }

    @Test
    void read_legacySnapshotWithScemeFieldsPresentAndConsistent_ignoresThemAndRehydratesFromConfig() {
        // A legacy snapshot that still carries versionScheme/calverFormat and they happen to agree
        // with config. Must still read fine (Jackson ignores unknown properties) and rehydrate per
        // config — this proves the fields are tolerated, not that they're consulted.
        Instant successAt = Instant.parse("2026-07-01T10:00:00Z");
        Instant attemptAt = Instant.parse("2026-07-01T10:00:05Z");

        String legacyJson = "{"
                + "\"applications\":[{"
                + "\"name\":\"test-calver-app\","
                + "\"currentValue\":\"2024.4.1\","
                + "\"currentLastSuccessAtEpochMillis\":" + successAt.toEpochMilli() + ","
                + "\"currentLastFailureAtEpochMillis\":null,"
                + "\"latestValue\":\"2024.5.2\","
                + "\"latestLastSuccessAtEpochMillis\":" + successAt.toEpochMilli() + ","
                + "\"latestLastFailureAtEpochMillis\":null,"
                + "\"versionScheme\":\"CALVER\","
                + "\"calverFormat\":\"YYYY.MM.MICRO\""
                + "}],"
                + "\"lastAttemptAtEpochMillis\":" + attemptAt.toEpochMilli()
                + "}";

        redisDataSource.value(String.class, String.class).set("scrape:snapshot", legacyJson);

        Optional<ScrapeSnapshot> read = sut.read();

        assertTrue(read.isPresent(), "a legacy snapshot carrying old scheme fields must still be readable");
        VersionApplication roundTripped = read.get().applications().getFirst();
        VersionValue current = roundTripped.current().value().orElseThrow();
        VersionValue latest = roundTripped.latest().value().orElseThrow();

        assertInstanceOf(CalverVersion.class, current,
                "config (calver) drives the result, not the legacy field (which happens to agree here)");
        assertInstanceOf(CalverVersion.class, latest,
                "config (calver) drives the result, not the legacy field (which happens to agree here)");
        assertEquals("2024.4.1", current.value());
        assertEquals("2024.5.2", latest.value());
    }

    @Test
    void read_legacySnapshotWhoseSchemeFieldLies_stillRehydratesFromConfigNotFromTheField() {
        // Decisive proof the legacy fields have NO EFFECT: the persisted versionScheme field LIES
        // (claims semver) for an app that is CONFIGURED as calver. The result must still be
        // CalverVersion, straight from config — never SemverVersion, which is what trusting the
        // stored field would produce.
        Instant successAt = Instant.parse("2026-07-01T10:00:00Z");
        Instant attemptAt = Instant.parse("2026-07-01T10:00:05Z");

        String lyingLegacyJson = "{"
                + "\"applications\":[{"
                + "\"name\":\"test-calver-app\","
                + "\"currentValue\":\"2024.4.1\","
                + "\"currentLastSuccessAtEpochMillis\":" + successAt.toEpochMilli() + ","
                + "\"currentLastFailureAtEpochMillis\":null,"
                + "\"latestValue\":\"2024.5.2\","
                + "\"latestLastSuccessAtEpochMillis\":" + successAt.toEpochMilli() + ","
                + "\"latestLastFailureAtEpochMillis\":null,"
                + "\"versionScheme\":\"SEMVER\""
                + "}],"
                + "\"lastAttemptAtEpochMillis\":" + attemptAt.toEpochMilli()
                + "}";

        redisDataSource.value(String.class, String.class).set("scrape:snapshot", lyingLegacyJson);

        Optional<ScrapeSnapshot> read = sut.read();

        assertTrue(read.isPresent(), "a legacy snapshot with a lying versionScheme field must still be readable");
        VersionApplication roundTripped = read.get().applications().getFirst();
        VersionValue current = roundTripped.current().value().orElseThrow();
        VersionValue latest = roundTripped.latest().value().orElseThrow();

        assertInstanceOf(CalverVersion.class, current,
                "config says test-calver-app is calver; the lying persisted versionScheme=SEMVER field "
                        + "must have no effect on the result");
        assertInstanceOf(CalverVersion.class, latest,
                "config says test-calver-app is calver; the lying persisted versionScheme=SEMVER field "
                        + "must have no effect on the result");
        assertEquals("2024.4.1", current.value());
        assertEquals("2024.5.2", latest.value());
    }

    // --- Issue 03: unconfigured entries are dropped at read time ---------------------------------
    //
    // Config defines the fleet. An entry whose app name is not in platform-config.apps has no
    // scheme declaration to interpret under and is stale residue (e.g. left behind after a config
    // removal, awaiting the next full-scrape rewrite). Rehydration must SKIP such an entry rather
    // than fail the whole read — a config removal should take effect on the very next read, on
    // every surface, rather than lingering for up to a scrape interval.
    //
    // NOTE on logging: no test in this file asserts on log output (there's no existing capture
    // pattern to reuse), so per the acceptance criterion "a debug/info log noting the skipped name"
    // is left to be verified by code review rather than by a new test-only logging-capture pattern
    // introduced just for this slice.

    @Test
    void read_snapshotWithAnUnconfiguredEntry_omitsIt_configuredEntriesUnaffected() {
        // Three entries: two configured (test-app semver, test-calver-app calver) and one
        // unconfigured (removed-app, standing in for an app removed from platform-config).
        Instant successAt = Instant.parse("2026-07-01T10:00:00Z");
        Instant attemptAt = Instant.parse("2026-07-01T10:00:05Z");

        VersionApplication configuredSemver = new VersionApplication(
                "test-app",
                SideObservation.resolved(new SemverVersion("1.0.0"), successAt),
                SideObservation.resolved(new SemverVersion("2.0.0"), successAt));
        VersionApplication configuredCalver = new VersionApplication(
                "test-calver-app",
                SideObservation.resolved(new CalverVersion("2024.4.1", CALVER_FORMAT), successAt),
                SideObservation.resolved(new CalverVersion("2024.5.2", CALVER_FORMAT), successAt));
        // write() never validates that an app is configured, so this works today to construct an
        // unconfigured entry without hand-building JSON.
        VersionApplication unconfigured = new VersionApplication(
                "removed-app",
                SideObservation.resolved(new SemverVersion("9.9.9"), successAt),
                SideObservation.resolved(new SemverVersion("9.9.9"), successAt));

        sut.write(List.of(configuredSemver, configuredCalver, unconfigured), attemptAt);

        Optional<ScrapeSnapshot> read = sut.read();

        assertTrue(read.isPresent(), "a snapshot containing an unconfigured entry must still be readable");
        List<VersionApplication> applications = read.get().applications();
        assertEquals(2, applications.size(),
                "the unconfigured entry (removed-app) must be dropped; the two configured entries "
                        + "must remain: " + applications);

        List<String> names = applications.stream().map(VersionApplication::name).toList();
        assertTrue(names.contains("test-app"), "configured entry test-app must be present: " + names);
        assertTrue(names.contains("test-calver-app"), "configured entry test-calver-app must be present: " + names);
        assertFalse(names.contains("removed-app"), "unconfigured entry removed-app must be omitted: " + names);

        VersionApplication rehydratedSemver = applications.stream()
                .filter(app -> app.name().equals("test-app"))
                .findFirst()
                .orElseThrow();
        assertEquals("1.0.0", rehydratedSemver.current().value().orElseThrow().value());
        assertEquals("2.0.0", rehydratedSemver.latest().value().orElseThrow().value());

        VersionApplication rehydratedCalver = applications.stream()
                .filter(app -> app.name().equals("test-calver-app"))
                .findFirst()
                .orElseThrow();
        assertEquals("2024.4.1", rehydratedCalver.current().value().orElseThrow().value());
        assertEquals("2024.5.2", rehydratedCalver.latest().value().orElseThrow().value());
    }

    @Test
    void read_snapshotWhereAllEntriesAreUnconfigured_rehydratesAsEmptyApplicationList() {
        // Every entry in the snapshot is unconfigured. The snapshot itself must still exist (a
        // present Optional, not Optional.empty()) with its lastAttemptAt intact — only the
        // application list is emptied out. This is NOT an error condition; it is expected during a
        // config transition (e.g. every previously-scraped app was just removed from config).
        Instant successAt = Instant.parse("2026-07-01T10:00:00Z");
        Instant attemptAt = Instant.parse("2026-07-01T10:00:05Z");

        VersionApplication ghostOne = new VersionApplication(
                "ghost-app-1",
                SideObservation.resolved(new SemverVersion("1.0.0"), successAt),
                SideObservation.resolved(new SemverVersion("1.0.0"), successAt));
        VersionApplication ghostTwo = new VersionApplication(
                "ghost-app-2",
                SideObservation.resolved(new SemverVersion("1.0.0"), successAt),
                SideObservation.resolved(new SemverVersion("1.0.0"), successAt));

        sut.write(List.of(ghostOne, ghostTwo), attemptAt);

        Optional<ScrapeSnapshot> read = sut.read();

        assertTrue(read.isPresent(),
                "a snapshot whose entries are all unconfigured must still rehydrate present (not "
                        + "Optional.empty()) — the snapshot itself exists, it's just empty of applications");
        ScrapeSnapshot snapshot = read.get();
        assertTrue(snapshot.applications().isEmpty(),
                "with every entry unconfigured, the rehydrated application list must be empty: "
                        + snapshot.applications());
        assertEquals(attemptAt, snapshot.lastAttemptAt(),
                "lastAttemptAt must still round-trip even when every entry is dropped");
    }

    // --- Issue 04: a scheme-mismatched stored value degrades its side to value-less, never the
    // read ----------------------------------------------------------------------------------------
    //
    // A stored value can predate a config change (semver -> calver flip) or simply not match the
    // app's declared calver-format. Rehydrating THAT side under the app's CONFIGURED parser then
    // throws InvalidVersionException. That failure must be caught per (app, side): the offending
    // side becomes value-less (empty value, empty lastSuccessAt) while its lastFailureAt is
    // preserved as stored — never let one bad string fail the whole snapshot read.
    //
    // Bad-string choice: "1.0.0" is invalid for test-calver-app's configured format
    // "YYYY.MM.MICRO". CalverFormat's first token, YYYY, compiles to the regex \d{4} (see
    // CalverFormat.tokenPattern), which requires exactly four digits. "1.0.0" starts with a
    // single-digit "1", so it fails to match the whole-format regex before the MM/MICRO tokens
    // even come into play — a clean, unambiguous non-match rather than a boundary case.

    @Test
    void read_currentSideValueFailsToParseUnderConfiguredScheme_degradesThatSideToValueless() {
        Instant staleSuccessAt = Instant.parse("2026-06-01T09:00:00Z");
        Instant failureAt = Instant.parse("2026-07-01T10:05:00Z");
        Instant latestSuccessAt = Instant.parse("2026-07-01T10:00:00Z");
        Instant attemptAt = Instant.parse("2026-07-01T10:05:00Z");

        // currentValue "1.0.0" is a semver-era leftover that does not match test-calver-app's
        // configured "YYYY.MM.MICRO" format (see bad-string rationale above). It carries BOTH a
        // (now stale, pre-flip) lastSuccessAt and a lastFailureAt from a subsequent failed scrape —
        // this is the shape a real degraded side would have: a prior value that can no longer be
        // trusted, plus record of the most recent failed attempt to refresh it.
        String json = "{"
                + "\"applications\":[{"
                + "\"name\":\"test-calver-app\","
                + "\"currentValue\":\"1.0.0\","
                + "\"currentLastSuccessAtEpochMillis\":" + staleSuccessAt.toEpochMilli() + ","
                + "\"currentLastFailureAtEpochMillis\":" + failureAt.toEpochMilli() + ","
                + "\"latestValue\":\"2024.5.2\","
                + "\"latestLastSuccessAtEpochMillis\":" + latestSuccessAt.toEpochMilli() + ","
                + "\"latestLastFailureAtEpochMillis\":null"
                + "}],"
                + "\"lastAttemptAtEpochMillis\":" + attemptAt.toEpochMilli()
                + "}";

        redisDataSource.value(String.class, String.class).set("scrape:snapshot", json);

        // Plain (uncaught) call: if a parse failure were allowed to propagate, this line itself
        // would throw and fail the test — no assertThrows/try-catch needed to prove non-throwing.
        Optional<ScrapeSnapshot> read = sut.read();

        assertTrue(read.isPresent(),
                "a snapshot containing one scheme-mismatched value must still be readable as a whole");
        VersionApplication app = read.get().applications().stream()
                .filter(a -> a.name().equals("test-calver-app"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("test-calver-app must remain in the fleet, degraded not dropped"));

        // Degraded side: value-less, lastSuccessAt dropped, lastFailureAt preserved as stored.
        assertTrue(app.current().value().isEmpty(),
                "current value must be dropped when it fails to parse under the configured scheme");
        assertTrue(app.current().lastSuccessAt().isEmpty(),
                "current lastSuccessAt must be dropped alongside the unparseable value (no \"as-of\" for a value that isn't there)");
        assertEquals(Optional.of(failureAt), app.current().lastFailureAt(),
                "current lastFailureAt must be preserved exactly as stored");

        // Unaffected side: latest parses fine under the same app's calver parser and round-trips normally.
        assertTrue(app.latest().value().isPresent(), "latest side must be unaffected by current's parse failure");
        assertEquals("2024.5.2", app.latest().value().orElseThrow().value());
        assertEquals(Optional.of(latestSuccessAt), app.latest().lastSuccessAt());
        assertTrue(app.latest().lastFailureAt().isEmpty());
    }

    @Test
    void read_otherAppsInTheSameSnapshotAreUnaffectedByOneAppsParseFailure() {
        Instant successAt = Instant.parse("2026-07-01T10:00:00Z");
        Instant staleSuccessAt = Instant.parse("2026-06-01T09:00:00Z");
        Instant failureAt = Instant.parse("2026-07-01T10:05:00Z");
        Instant attemptAt = Instant.parse("2026-07-01T10:05:00Z");

        // Three entries: test-app (fully healthy, semver), test-calver-app (degraded current side,
        // healthy latest side). test-app must be completely untouched by test-calver-app's failure.
        String json = "{"
                + "\"applications\":[{"
                + "\"name\":\"test-app\","
                + "\"currentValue\":\"1.2.3\","
                + "\"currentLastSuccessAtEpochMillis\":" + successAt.toEpochMilli() + ","
                + "\"currentLastFailureAtEpochMillis\":null,"
                + "\"latestValue\":\"1.3.0\","
                + "\"latestLastSuccessAtEpochMillis\":" + successAt.toEpochMilli() + ","
                + "\"latestLastFailureAtEpochMillis\":null"
                + "},{"
                + "\"name\":\"test-calver-app\","
                + "\"currentValue\":\"1.0.0\","
                + "\"currentLastSuccessAtEpochMillis\":" + staleSuccessAt.toEpochMilli() + ","
                + "\"currentLastFailureAtEpochMillis\":" + failureAt.toEpochMilli() + ","
                + "\"latestValue\":\"2024.5.2\","
                + "\"latestLastSuccessAtEpochMillis\":" + successAt.toEpochMilli() + ","
                + "\"latestLastFailureAtEpochMillis\":null"
                + "}],"
                + "\"lastAttemptAtEpochMillis\":" + attemptAt.toEpochMilli()
                + "}";

        redisDataSource.value(String.class, String.class).set("scrape:snapshot", json);

        Optional<ScrapeSnapshot> read = sut.read();

        assertTrue(read.isPresent());
        List<VersionApplication> applications = read.get().applications();
        assertEquals(2, applications.size(),
                "both apps must remain in the fleet (degraded, not dropped): " + applications);

        VersionApplication healthy = applications.stream()
                .filter(a -> a.name().equals("test-app"))
                .findFirst()
                .orElseThrow();
        assertTrue(healthy.current().value().isPresent(), "test-app current must be completely unaffected");
        assertEquals("1.2.3", healthy.current().value().orElseThrow().value());
        assertEquals(Optional.of(successAt), healthy.current().lastSuccessAt());
        assertTrue(healthy.current().lastFailureAt().isEmpty());
        assertTrue(healthy.latest().value().isPresent(), "test-app latest must be completely unaffected");
        assertEquals("1.3.0", healthy.latest().value().orElseThrow().value());
        assertEquals(Optional.of(successAt), healthy.latest().lastSuccessAt());

        VersionApplication degraded = applications.stream()
                .filter(a -> a.name().equals("test-calver-app"))
                .findFirst()
                .orElseThrow();
        assertTrue(degraded.current().value().isEmpty(),
                "test-calver-app current must be degraded to value-less");
        assertEquals(Optional.of(failureAt), degraded.current().lastFailureAt());
        assertTrue(degraded.latest().value().isPresent(),
                "test-calver-app latest must rehydrate normally alongside its own failed current side");
        assertEquals("2024.5.2", degraded.latest().value().orElseThrow().value());
    }
}
