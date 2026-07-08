package org.yardship.core.domain.primitives;

import org.junit.jupiter.api.Test;
import org.yardship.core.domain.exceptions.InvalidDomainObjectException;
import org.yardship.core.domain.primitives.SemverVersion;
import org.yardship.core.domain.primitives.SideObservation;
import org.yardship.core.domain.primitives.VersionValue;
import org.yardship.core.domain.primitives.VersionApplication;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link VersionApplication} after the slice 01 shape change: each side is now a
 * {@link SideObservation} rather than a bare {@link VersionValue}.
 *
 * <p>{@link VersionApplication#isOld()}, {@link VersionApplication#drift()}, and
 * {@link VersionApplication#hasDriftAtLeast} must still work for resolved apps (both sides have a
 * value). This slice only ever produces resolved observations, so those predicates must be correct
 * for the resolved case.
 */
public class VersionApplicationTests {

    private static final Instant FIXED_NOW = Instant.parse("2026-07-01T10:00:00Z");

    private final String validName = "Dummy Application";
    // Both observations are resolved (value + lastSuccessAt present, lastFailureAt absent).
    private final SideObservation current = SideObservation.resolved(new SemverVersion("1.2.3"), FIXED_NOW);
    private final SideObservation latest = SideObservation.resolved(new SemverVersion("3.2.1"), FIXED_NOW);

    /** Convenience — makes per-test version assertions concise. */
    private SideObservation obs(String version) {
        return SideObservation.resolved(new SemverVersion(version), FIXED_NOW);
    }

    @Test
    void VersionApplications_needsANameAndValidVersions() {
        assertThrows(InvalidDomainObjectException.class, () ->
                new VersionApplication("", current, latest));
        assertThrows(InvalidDomainObjectException.class, () ->
                new VersionApplication(null, current, latest));
        assertThrows(InvalidDomainObjectException.class, () ->
                new VersionApplication(validName, null, latest));
        assertThrows(InvalidDomainObjectException.class, () ->
                new VersionApplication(validName, current, null));
    }

    @Test
    void VersionApplications_canCheckIfTheyAreOld() {
        VersionApplication oldApplication = new VersionApplication(validName, current, latest);
        VersionApplication up2dateApplication = new VersionApplication(validName, latest, latest);

        assertTrue(oldApplication.isOld());
        assertFalse(up2dateApplication.isOld());
    }

    @Test
    void drift_isMajor_whenMajorBehind() {
        VersionApplication app = new VersionApplication(validName, obs("1.1.1"), obs("2.2.2"));
        assertEquals(VersionValue.Diff.MAJOR, app.drift());
    }

    @Test
    void drift_isMinor_whenMinorBehind() {
        VersionApplication app = new VersionApplication(validName, obs("2.1.0"), obs("2.2.0"));
        assertEquals(VersionValue.Diff.MINOR, app.drift());
    }

    @Test
    void drift_isPatch_whenPatchBehind() {
        VersionApplication app = new VersionApplication(validName, obs("2.2.1"), obs("2.2.2"));
        assertEquals(VersionValue.Diff.PATCH, app.drift());
    }

    @Test
    void drift_isNone_whenVersionsEqual() {
        VersionApplication app = new VersionApplication(validName, obs("2.2.2"), obs("2.2.2"));
        assertEquals(VersionValue.Diff.NONE, app.drift());
    }

    @Test
    void drift_isNone_whenCurrentIsAheadOfLatest() {
        VersionApplication app = new VersionApplication(validName, obs("3.0.0"), obs("2.0.0"));
        assertEquals(VersionValue.Diff.NONE, app.drift());
    }

    @Test
    void drift_isPatch_whenOnlySuffixDifferenceWhileBehind() {
        VersionApplication app = new VersionApplication(validName, obs("2.0.0-rc1"), obs("2.0.0"));
        assertEquals(VersionValue.Diff.PATCH, app.drift());
    }

    // hasDriftAtLeast: "does this app drift by at least the given minimum severity?"
    // Drift ordering contract: NONE < PATCH < MINOR < MAJOR.

    @Test
    void hasDriftAtLeast_isFalseAtEveryThreshold_whenCurrent() {
        VersionApplication current = new VersionApplication(validName, obs("2.2.2"), obs("2.2.2"));

        assertFalse(current.hasDriftAtLeast(VersionValue.Diff.PATCH));
        assertFalse(current.hasDriftAtLeast(VersionValue.Diff.MINOR));
        assertFalse(current.hasDriftAtLeast(VersionValue.Diff.MAJOR));
    }

    @Test
    void hasDriftAtLeast_patchApp_meetsOnlyPatchThreshold() {
        VersionApplication patchBehind = new VersionApplication(validName, obs("2.2.1"), obs("2.2.2"));

        assertTrue(patchBehind.hasDriftAtLeast(VersionValue.Diff.PATCH));
        assertFalse(patchBehind.hasDriftAtLeast(VersionValue.Diff.MINOR));
        assertFalse(patchBehind.hasDriftAtLeast(VersionValue.Diff.MAJOR));
    }

    @Test
    void hasDriftAtLeast_minorApp_meetsPatchAndMinorThresholds() {
        VersionApplication minorBehind = new VersionApplication(validName, obs("2.1.0"), obs("2.2.0"));

        assertTrue(minorBehind.hasDriftAtLeast(VersionValue.Diff.PATCH));
        assertTrue(minorBehind.hasDriftAtLeast(VersionValue.Diff.MINOR));
        assertFalse(minorBehind.hasDriftAtLeast(VersionValue.Diff.MAJOR));
    }

    @Test
    void hasDriftAtLeast_majorApp_meetsEveryThreshold() {
        VersionApplication majorBehind = new VersionApplication(validName, obs("1.1.1"), obs("2.2.2"));

        assertTrue(majorBehind.hasDriftAtLeast(VersionValue.Diff.PATCH));
        assertTrue(majorBehind.hasDriftAtLeast(VersionValue.Diff.MINOR));
        assertTrue(majorBehind.hasDriftAtLeast(VersionValue.Diff.MAJOR));
    }

    // --- Issue 03: Unresolved apps (value-less sides) -----------------------------------------
    //
    // VersionApplication now accepts sides without a value (pending/failed sides). `isResolved()`
    // returns true only when BOTH sides have a value. `drift()` and `isOld()` throw
    // IllegalStateException when unresolved — callers must check `isResolved()` first.
    // An Unresolved app must NEVER silently report drift NONE.

    /** Helper for a never-attempted side (pending, all-empty). */
    private SideObservation pending() {
        return new SideObservation(Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** Helper for a never-succeeded-failed side (failed, no prior value). */
    private SideObservation failedNeverSucceeded() {
        return new SideObservation(
                Optional.empty(), Optional.empty(), Optional.of(FIXED_NOW));
    }

    @Test
    void isResolved_returnsTrue_whenBothSidesHaveValues() {
        VersionApplication app = new VersionApplication(validName, current, latest);
        assertTrue(app.isResolved(), "an app where both sides carry a value must be resolved");
    }

    @Test
    void isResolved_returnsFalse_whenCurrentHasNoValue() {
        VersionApplication app = new VersionApplication(validName, pending(), latest);
        assertFalse(app.isResolved(), "an app with a value-less current side must not be resolved");
    }

    @Test
    void isResolved_returnsFalse_whenLatestHasNoValue() {
        VersionApplication app = new VersionApplication(validName, current, pending());
        assertFalse(app.isResolved(), "an app with a value-less latest side must not be resolved");
    }

    @Test
    void isResolved_returnsFalse_whenBothSidesHaveNoValue() {
        VersionApplication app = new VersionApplication(validName, pending(), pending());
        assertFalse(app.isResolved(), "an app where both sides are pending must not be resolved");
    }

    @Test
    void construction_acceptsValuelessSide_withoutThrowing() {
        // VersionApplication must not reject a value-less (pending or failed) side — the not-null
        // guard is on the SideObservation object itself, not on whether it holds a value.
        assertDoesNotThrow(() -> new VersionApplication(validName, pending(), latest),
                "constructing with a pending current side must not throw");
        assertDoesNotThrow(() -> new VersionApplication(validName, current, failedNeverSucceeded()),
                "constructing with a never-succeeded-failed latest side must not throw");
    }

    @Test
    void drift_throwsIllegalStateException_whenCurrentSideHasNoValue() {
        // Contract: calling drift() on an Unresolved app is a programming error — callers MUST
        // check isResolved() first. The method throws IllegalStateException so that bugs fail loudly.
        // This prevents the critical invariant violation: an Unresolved app silently reporting NONE.
        VersionApplication app = new VersionApplication(validName, pending(), latest);
        assertThrows(IllegalStateException.class, app::drift,
                "drift() must throw IllegalStateException when current has no value (not silently return NONE)");
    }

    @Test
    void drift_throwsIllegalStateException_whenLatestSideHasNoValue() {
        VersionApplication app = new VersionApplication(validName, current, pending());
        assertThrows(IllegalStateException.class, app::drift,
                "drift() must throw IllegalStateException when latest has no value");
    }

    @Test
    void drift_throwsIllegalStateException_whenBothSidesHaveNoValue() {
        VersionApplication app = new VersionApplication(validName, pending(), pending());
        assertThrows(IllegalStateException.class, app::drift,
                "drift() must throw IllegalStateException when both sides have no value");
    }

    @Test
    void drift_throwsIllegalStateException_forNeverSucceededFailedSide() {
        // A never-succeeded-failed side (no value, no lastSuccessAt, lastFailureAt present) must
        // also trigger the guard — the app is still Unresolved.
        VersionApplication app = new VersionApplication(validName, failedNeverSucceeded(), latest);
        assertThrows(IllegalStateException.class, app::drift,
                "drift() must throw for a never-succeeded-failed side (Unresolved)");
    }

    @Test
    void unresolvedApp_neverReportsDriftNone_criticalInvariant() {
        // THE critical invariant from AC: an Unresolved app must NEVER return NONE from drift().
        // "NONE" in the frontend means "up to date" — an unknown app must not be silently treated as
        // up-to-date. The guard must throw rather than fall through to the NONE branch.
        VersionApplication app = new VersionApplication(validName, pending(), latest);
        try {
            VersionValue.Diff d = app.drift();
            // If we reach here, the invariant is violated.
            assertNotEquals(VersionValue.Diff.NONE, d,
                    "an Unresolved app returned drift=" + d + " instead of throwing — this violates the invariant");
            fail("drift() must throw for an Unresolved app; it returned " + d + " instead");
        } catch (IllegalStateException expected) {
            // Pass: the guard threw, so NONE was never returned.
        }
    }

    @Test
    void isOld_throwsIllegalStateException_whenCurrentHasNoValue() {
        VersionApplication app = new VersionApplication(validName, pending(), latest);
        assertThrows(IllegalStateException.class, app::isOld,
                "isOld() must throw IllegalStateException for an Unresolved app");
    }

    @Test
    void isOld_throwsIllegalStateException_whenLatestHasNoValue() {
        VersionApplication app = new VersionApplication(validName, current, pending());
        assertThrows(IllegalStateException.class, app::isOld,
                "isOld() must throw IllegalStateException when latest has no value");
    }

    // --- Issue 05: hasFailedScrape() — at least one side's newest attempt was a failure ----------
    //
    // hasFailedScrape() is the domain predicate the list_applications_with_failed_scrapes MCP tool
    // filters on. It delegates to SideObservation.failedRefresh() for each side.
    //
    // Four states (mirrors the MCP tool's four-state matrix):
    //   pending              — no success, no failure                        → false
    //   fresh success        — success, no failure (or success newer)        → false
    //   failed-refresh       — had a value, then newest attempt failed       → true
    //   never-succeeded-fail — never got a value, but a failure was recorded → true

    /** A side with a prior success that was then followed by a newer failure. */
    private SideObservation failedRefreshAfterSuccess() {
        Instant successAt = FIXED_NOW.minusSeconds(60);
        Instant failureAt = FIXED_NOW; // failure is strictly newer
        return new SideObservation(
                Optional.of(new SemverVersion("1.0.0")),
                Optional.of(successAt),
                Optional.of(failureAt));
    }

    @Test
    void hasFailedScrape_returnsFalse_whenBothSidesFreshSuccess() {
        // Both sides resolved, no failure recorded — nothing has failed.
        VersionApplication app = new VersionApplication(validName, current, latest);
        assertFalse(app.hasFailedScrape(),
                "an app with both sides freshly resolved must not report a failed scrape");
    }

    @Test
    void hasFailedScrape_returnsFalse_whenBothSidesPending() {
        // Neither side has been attempted at all — pending is NOT a failure.
        VersionApplication app = new VersionApplication(validName, pending(), pending());
        assertFalse(app.hasFailedScrape(),
                "an app where both sides are pending (never attempted) must not report a failed scrape; "
                        + "calling a scrape that never ran 'failed' would be a lie");
    }

    @Test
    void hasFailedScrape_returnsTrue_whenCurrentSideHadValueThenFailed() {
        // Current side: had a value, but the newest attempt failed (failed-refresh state).
        VersionApplication app = new VersionApplication(validName, failedRefreshAfterSuccess(), latest);
        assertTrue(app.hasFailedScrape(),
                "an app whose current side had a value then the newest attempt failed must report a failed scrape");
    }

    @Test
    void hasFailedScrape_returnsTrue_whenLatestSideNeverSucceededAndFailed() {
        // Latest side: never got a value, but a failure was recorded.
        VersionApplication app = new VersionApplication(validName, current, failedNeverSucceeded());
        assertTrue(app.hasFailedScrape(),
                "an app whose latest side never succeeded but has a failure stamp must report a failed scrape");
    }

    @Test
    void hasFailedScrape_returnsTrue_whenEitherSideFailedRefresh() {
        // If even one side has a failed scrape, the whole app is considered to have a failed scrape.
        VersionApplication failedCurrentApp = new VersionApplication(validName, failedRefreshAfterSuccess(), latest);
        VersionApplication failedLatestApp  = new VersionApplication(validName, current, failedRefreshAfterSuccess());

        assertTrue(failedCurrentApp.hasFailedScrape(),
                "failed-refresh on current side must trigger hasFailedScrape");
        assertTrue(failedLatestApp.hasFailedScrape(),
                "failed-refresh on latest side must trigger hasFailedScrape");
    }

    @Test
    void hasFailedScrape_returnsFalse_whenOnlySideIsPendingWithNoFailure() {
        // A mixed app: one side resolved, one side pending (never attempted). Neither has failed.
        VersionApplication app = new VersionApplication(validName, current, pending());
        assertFalse(app.hasFailedScrape(),
                "a pending side with no failure must not count as a failed scrape");
    }
}
