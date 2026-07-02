package org.yardship.unit.adapters.in.metrics;

import org.junit.jupiter.api.Test;
import org.yardship.adapters.in.metrics.PrometheusDriftRenderer;
import org.yardship.core.domain.primitives.SemverVersion;
import org.yardship.core.domain.primitives.SideObservation;
import org.yardship.core.domain.primitives.VersionValue;
import org.yardship.core.domain.primitives.VersionApplication;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrometheusDriftRendererTests {

    private static final String HELP_LINE =
            "# HELP pu2d_version_drift_level How far the deployed version is behind latest "
                    + "(0=current, 1=patch, 2=minor, 3=major)";
    private static final String TYPE_LINE = "# TYPE pu2d_version_drift_level gauge";

    private static final Instant NOW = Instant.parse("2026-07-01T10:00:00Z");
    private final PrometheusDriftRenderer sut = new PrometheusDriftRenderer();

    private static SideObservation obs(String version) {
        return SideObservation.resolved(new SemverVersion(version), NOW);
    }

    /** Resolved observation with a recorded failure AFTER the success — models a "stuck" side. */
    private static SideObservation obsWithFailure(String version, Instant success, Instant failure) {
        return new SideObservation(
                Optional.of(new SemverVersion(version)),
                Optional.of(success),
                Optional.of(failure));
    }

    /** A side that failed on first attempt and never succeeded (no value, no lastSuccessAt). */
    private static SideObservation obsNeverSucceeded(Instant failure) {
        return new SideObservation(Optional.empty(), Optional.empty(), Optional.of(failure));
    }

    @Test
    void render_emitsHeaderOnce_andOneSampleLinePerApp() {
        VersionApplication major = new VersionApplication("major-app", obs("1.1.1"), obs("2.2.2"));
        VersionApplication minor = new VersionApplication("minor-app", obs("2.1.0"), obs("2.2.0"));
        VersionApplication patch = new VersionApplication("patch-app", obs("2.2.1"), obs("2.2.2"));
        VersionApplication current = new VersionApplication("current-app", obs("2.0.0"), obs("2.0.0"));

        String output = sut.render(List.of(major, minor, patch, current));

        assertTrue(output.contains(HELP_LINE), "expected single HELP line in: " + output);
        assertTrue(output.contains(TYPE_LINE), "expected single TYPE line in: " + output);
        assertEquals(1, countOccurrences(output, HELP_LINE), "HELP must appear exactly once");
        assertEquals(1, countOccurrences(output, TYPE_LINE), "TYPE must appear exactly once");

        assertTrue(output.contains("pu2d_version_drift_level{app=\"major-app\"} 3"), output);
        assertTrue(output.contains("pu2d_version_drift_level{app=\"minor-app\"} 2"), output);
        assertTrue(output.contains("pu2d_version_drift_level{app=\"patch-app\"} 1"), output);
        assertTrue(output.contains("pu2d_version_drift_level{app=\"current-app\"} 0"), output);
    }

    @Test
    void render_emptyList_emitsHeaderOnly_noSampleLines() {
        String output = sut.render(List.of());

        assertTrue(output.contains(HELP_LINE), "expected HELP line in: " + output);
        assertTrue(output.contains(TYPE_LINE), "expected TYPE line in: " + output);
        assertFalse(output.contains("pu2d_version_drift_level{"),
                "expected no sample lines in: " + output);
    }

    @Test
    void render_escapesLabelValue_perExpositionSpec() {
        VersionApplication weird = new VersionApplication("we\"ird\\name", obs("1.1.1"), obs("2.2.2"));

        String output = sut.render(List.of(weird));

        assertTrue(output.contains("pu2d_version_drift_level{app=\"we\\\"ird\\\\name\"} 3"),
                "expected escaped label value in: " + output);
    }

    @Test
    void render_escapesNewlineInLabelValue_asLiteralBackslashN() {
        VersionApplication multiline = new VersionApplication("line1\nline2", obs("1.1.1"), obs("2.2.2"));

        String output = sut.render(List.of(multiline));

        // The embedded newline must become a literal backslash-n, not a real line break,
        // otherwise the sample would split across two physical lines and break parsing.
        assertTrue(output.contains("pu2d_version_drift_level{app=\"line1\\nline2\"} 3"),
                "expected newline escaped as literal \\n in: " + output);
    }

    @Test
    void render_everyLineNonEmpty_andEndsWithNewline() {
        VersionApplication app = new VersionApplication("some-app", obs("1.1.1"), obs("2.2.2"));

        String output = sut.render(List.of(app));

        assertTrue(output.endsWith("\n"), "output must end with newline: " + output);
        String[] lines = output.split("\n", -1);
        // last token after trailing newline is empty; ignore it
        for (int i = 0; i < lines.length - 1; i++) {
            assertFalse(lines[i].isEmpty(), "line " + i + " must be non-empty in: " + output);
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    // -------------------------------------------------------------------------
    // Issue 04 — per-(app, side) freshness gauges
    // -------------------------------------------------------------------------

    @Test
    void render_resolvedApp_emitsSuccessTimestampForBothSides() {
        Instant currentSuccess = Instant.parse("2026-07-01T08:00:00Z");
        Instant latestSuccess  = Instant.parse("2026-07-01T09:00:00Z");
        SideObservation currentObs = SideObservation.resolved(new SemverVersion("1.0.0"), currentSuccess);
        SideObservation latestObs  = SideObservation.resolved(new SemverVersion("2.0.0"), latestSuccess);
        VersionApplication app = new VersionApplication("my-app", currentObs, latestObs);

        String output = sut.render(List.of(app));

        assertTrue(output.contains(
                "pu2d_scrape_last_success_timestamp_seconds{app=\"my-app\",side=\"current\"} "
                        + currentSuccess.getEpochSecond()),
                "expected current-side success timestamp in: " + output);
        assertTrue(output.contains(
                "pu2d_scrape_last_success_timestamp_seconds{app=\"my-app\",side=\"latest\"} "
                        + latestSuccess.getEpochSecond()),
                "expected latest-side success timestamp in: " + output);
    }

    @Test
    void render_sideWithRecordedFailure_emitsFailureTimestamp() {
        Instant success = Instant.parse("2026-07-01T08:00:00Z");
        Instant failure = Instant.parse("2026-07-01T09:30:00Z");
        // current side: resolved but has a newer failure (failedRefresh = true)
        SideObservation failedCurrent = obsWithFailure("1.0.0", success, failure);
        // latest side: healthy, no failure
        SideObservation goodLatest = SideObservation.resolved(new SemverVersion("2.0.0"), success);
        VersionApplication app = new VersionApplication("my-app", failedCurrent, goodLatest);

        String output = sut.render(List.of(app));

        assertTrue(output.contains(
                "pu2d_scrape_last_failure_timestamp_seconds{app=\"my-app\",side=\"current\"} "
                        + failure.getEpochSecond()),
                "expected failure timestamp for current side in: " + output);
        assertFalse(output.contains("pu2d_scrape_last_failure_timestamp_seconds{app=\"my-app\",side=\"latest\"}"),
                "expected NO failure series for healthy latest side in: " + output);
    }

    @Test
    void render_neverSucceededSide_emitsFailureButNoSuccessTimestamp() {
        Instant failure = Instant.parse("2026-07-01T09:00:00Z");
        // current side: never succeeded — no value, no lastSuccessAt, only lastFailureAt
        SideObservation neverSucceeded = obsNeverSucceeded(failure);
        // latest side: healthy
        SideObservation goodLatest = SideObservation.resolved(
                new SemverVersion("2.0.0"), Instant.parse("2026-07-01T08:00:00Z"));
        // App is unresolved (current has no value)
        VersionApplication app = new VersionApplication("bad-app", neverSucceeded, goodLatest);

        String output = sut.render(List.of(app));

        assertTrue(output.contains(
                "pu2d_scrape_last_failure_timestamp_seconds{app=\"bad-app\",side=\"current\"} "
                        + failure.getEpochSecond()),
                "expected failure timestamp for never-succeeded side in: " + output);
        assertFalse(output.contains(
                "pu2d_scrape_last_success_timestamp_seconds{app=\"bad-app\",side=\"current\"}"),
                "expected NO success series for never-succeeded side in: " + output);
    }

    @Test
    void render_unresolvedApp_emitsNoDriftSeries_butSideLevelGaugesStillEmitted() {
        Instant latestSuccess  = Instant.parse("2026-07-01T08:00:00Z");
        Instant currentFailure = Instant.parse("2026-07-01T09:00:00Z");
        // current side: never succeeded (app is Unresolved)
        SideObservation unresolvedCurrent = obsNeverSucceeded(currentFailure);
        // latest side: resolved normally
        SideObservation resolvedLatest = SideObservation.resolved(new SemverVersion("2.0.0"), latestSuccess);
        VersionApplication unresolved = new VersionApplication("bad-app", unresolvedCurrent, resolvedLatest);

        String output = sut.render(List.of(unresolved));

        // Drift series MUST be absent for Unresolved app — drift is undefined
        assertFalse(output.contains("pu2d_version_drift_level{app=\"bad-app\""),
                "Unresolved app must NOT appear in drift gauge; output: " + output);
        // Per-side success gauge IS emitted for the resolved latest side
        assertTrue(output.contains(
                "pu2d_scrape_last_success_timestamp_seconds{app=\"bad-app\",side=\"latest\"} "
                        + latestSuccess.getEpochSecond()),
                "expected success timestamp for resolved latest side in: " + output);
        // Per-side failure gauge IS emitted for the failed current side
        assertTrue(output.contains(
                "pu2d_scrape_last_failure_timestamp_seconds{app=\"bad-app\",side=\"current\"} "
                        + currentFailure.getEpochSecond()),
                "expected failure timestamp for failed current side in: " + output);
    }

    @Test
    void render_newMetricFamilies_haveHelpAndTypeHeaders() {
        // Use an app with a failure so both new families have at least one sample
        Instant success = Instant.parse("2026-07-01T08:00:00Z");
        Instant failure = Instant.parse("2026-07-01T09:00:00Z");
        SideObservation failedCurrent = obsWithFailure("1.0.0", success, failure);
        SideObservation goodLatest = SideObservation.resolved(new SemverVersion("2.0.0"), success);
        VersionApplication app = new VersionApplication("some-app", failedCurrent, goodLatest);

        String output = sut.render(List.of(app));

        assertTrue(output.contains("# HELP pu2d_scrape_last_success_timestamp_seconds "),
                "expected HELP line for success gauge in: " + output);
        assertTrue(output.contains("# TYPE pu2d_scrape_last_success_timestamp_seconds gauge"),
                "expected TYPE line for success gauge in: " + output);
        assertTrue(output.contains("# HELP pu2d_scrape_last_failure_timestamp_seconds "),
                "expected HELP line for failure gauge in: " + output);
        assertTrue(output.contains("# TYPE pu2d_scrape_last_failure_timestamp_seconds gauge"),
                "expected TYPE line for failure gauge in: " + output);
    }

    @Test
    void render_metricFamiliesAreNotInterleaved() {
        // App that produces all three families: drift (resolved), success, and failure
        Instant success = Instant.parse("2026-07-01T08:00:00Z");
        Instant failure = Instant.parse("2026-07-01T09:00:00Z");
        SideObservation failedCurrent = obsWithFailure("1.0.0", success, failure);
        SideObservation goodLatest = SideObservation.resolved(new SemverVersion("2.0.0"), success);
        VersionApplication app = new VersionApplication("some-app", failedCurrent, goodLatest);

        String output = sut.render(List.of(app));

        int driftTypeIdx   = output.indexOf("# TYPE pu2d_version_drift_level gauge");
        int successHelpIdx = output.indexOf("# HELP pu2d_scrape_last_success_timestamp_seconds");
        int successTypeIdx = output.indexOf("# TYPE pu2d_scrape_last_success_timestamp_seconds gauge");
        int failureHelpIdx = output.indexOf("# HELP pu2d_scrape_last_failure_timestamp_seconds");

        assertTrue(driftTypeIdx >= 0,   "drift TYPE line must be present");
        assertTrue(successHelpIdx > driftTypeIdx,
                "success HELP must come after the entire drift family (no interleaving)");
        assertTrue(successTypeIdx > successHelpIdx,
                "success TYPE must follow success HELP");
        assertTrue(failureHelpIdx > successTypeIdx,
                "failure HELP must come after the entire success family (no interleaving)");
    }

    @Test
    void render_successTimestamp_usesEpochSeconds_notMilliseconds() {
        // Pin the unit: the sample value must match getEpochSecond() (not toEpochMilli())
        Instant ts = Instant.parse("2026-07-01T12:34:56Z");
        SideObservation side = SideObservation.resolved(new SemverVersion("1.0.0"), ts);
        VersionApplication app = new VersionApplication("ts-app", side, obs("1.0.0"));

        String output = sut.render(List.of(app));

        // epoch seconds
        String expectedSeconds = String.valueOf(ts.getEpochSecond());
        // epoch millis — must NOT appear as the gauge value
        String epochMillis = String.valueOf(ts.toEpochMilli());

        assertTrue(output.contains(
                "pu2d_scrape_last_success_timestamp_seconds{app=\"ts-app\",side=\"current\"} "
                        + expectedSeconds),
                "expected epoch-seconds value " + expectedSeconds + " in: " + output);
        assertFalse(output.contains(
                "pu2d_scrape_last_success_timestamp_seconds{app=\"ts-app\",side=\"current\"} "
                        + epochMillis),
                "gauge value must be epoch SECONDS not millis; found millis value in: " + output);
    }
}
