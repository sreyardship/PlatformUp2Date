package org.yardship.unit.adapters.out.versionsource.current.httpprometheus;

import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.current.httpprometheus.HttpPrometheusCurrentSource;
import org.yardship.adapters.out.versionsource.current.httpprometheus.PrometheusBodyFetch;
import org.yardship.adapters.out.versionsource.regex.RegexVersionExtractor;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import org.yardship.core.domain.primitives.VersionValue;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link HttpPrometheusCurrentSource} as a pure POJO — NO Arc, NO real HTTP. It is
 * constructed directly with a fake {@link PrometheusBodyFetch} supplying a fixed body string, so
 * the source can be driven with hand-written Prometheus-exposition fixtures without a stub server
 * — mirroring how {@code HttpHeaderCurrentSourceTests} fakes {@code HttpHeaderFetch}.
 *
 * <p>{@code docs/adr/0033-http-prometheus-current-source.md} is the binding specification. Real
 * parsing (comment-skipping, escaping, exact metric matching, document order) is exhaustively owned
 * by {@code PrometheusExpositionTests} at the pure-parser level and is NOT re-asserted here in
 * detail — this class exercises the source's OWN behavior on top of that: first-sample-wins
 * selection, {@code version-label} resolution, and the four failure messages this source itself
 * raises (a fifth — non-2xx — belongs to the production {@code PrometheusBodyFetch} and is an
 * integration-level concern). Of those four, three — metric absent, version-label absent, and
 * an unparseable/empty value — are asserted to never leak any slice of the fetched body via a
 * recognisable sentinel string. The fourth, "selector matched nothing", is the ADR-sanctioned
 * exception: it is permitted, bounded and scoped to the named metric, to quote label sets actually
 * seen for that metric, so its own tests assert what IS and is not named rather than a blanket
 * no-leak guarantee.
 */
class HttpPrometheusCurrentSourceTests {

    private static final VersionParser SEMVER_PARSER = new VersionParser(VersionScheme.SEMVER);
    private static final String URL = "https://blackbox.example.com/metrics";
    private static final String METRIC = "blackbox_exporter_build_info";
    private static final String DEFAULT_VERSION_LABEL = "version";
    private static final String SENTINEL = "SENTINEL_DO_NOT_LEAK_INTO_ANY_MESSAGE_9f8e7d6c";

    // -----------------------------------------------------------------------
    // Happy path
    // -----------------------------------------------------------------------

    @Test
    void version_happyPath_blackboxShapedBody_readsTheVersionLabel() {
        String body = """
                # HELP blackbox_exporter_build_info A metric with a constant '1' value labeled by version, revision, branch.
                # TYPE blackbox_exporter_build_info gauge
                blackbox_exporter_build_info{branch="HEAD",goversion="go1.22.4",revision="0ec2a6b",version="0.25.0"} 1
                """;
        HttpPrometheusCurrentSource source = source(body, METRIC, DEFAULT_VERSION_LABEL);

        VersionValue result = source.version();

        assertEquals("0.25.0", result.value());
    }

    // -----------------------------------------------------------------------
    // First-sample-wins, no error, when several samples of the metric match
    // -----------------------------------------------------------------------

    @Test
    void version_takesTheFirstMatchingSample_whenSeveralSamplesOfTheMetricExist_withNoError() {
        String body = """
                blackbox_exporter_build_info{instance="a",version="0.24.0"} 1
                blackbox_exporter_build_info{instance="b",version="0.25.0"} 1
                """;
        HttpPrometheusCurrentSource source = source(body, METRIC, DEFAULT_VERSION_LABEL);

        VersionValue result = source.version();

        assertEquals("0.24.0", result.value(),
                "first-sample-wins: the FIRST matching sample in document order must be used, "
                        + "with no error raised over the conflicting second sample");
    }

    // -----------------------------------------------------------------------
    // version-label: honoured when configured, defaults to "version" when the source is built
    // with the default label (the factory owns the actual default-resolution logic; this proves
    // the source honours whatever label string it is given).
    // -----------------------------------------------------------------------

    @Test
    void version_honoursACustomVersionLabel_overTheDefaultVersionLabel() {
        String body = """
                blackbox_exporter_build_info{version="9.9.9",build_version="1.2.3"} 1
                """;
        HttpPrometheusCurrentSource source = source(body, METRIC, "build_version");

        VersionValue result = source.version();

        assertEquals("1.2.3", result.value(),
                "with version-label configured to 'build_version', that label must be read, "
                        + "not the default 'version' label");
    }

    // -----------------------------------------------------------------------
    // The three distinct failure messages this source itself raises
    // -----------------------------------------------------------------------

    @Test
    void version_throws_whenTheMetricIsAbsentFromTheBody_namingTheMetricAndUrl() {
        String body = "some_other_metric{version=\"1.0.0\"} 1\n# " + SENTINEL + "\n";
        HttpPrometheusCurrentSource source = source(body, METRIC, DEFAULT_VERSION_LABEL);

        IllegalStateException ex = assertThrows(IllegalStateException.class, source::version);

        assertTrue(ex.getMessage().contains(METRIC), "must name the metric; was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains(URL), "must name the url; was: " + ex.getMessage());
        assertFalse(ex.getMessage().contains(SENTINEL),
                "no failure message may embed the body; was: " + ex.getMessage());
    }

    @Test
    void version_throws_whenTheMatchedSampleLacksTheVersionLabel() {
        String body = "blackbox_exporter_build_info{other=\"" + SENTINEL + "\"} 1\n";
        HttpPrometheusCurrentSource source = source(body, METRIC, DEFAULT_VERSION_LABEL);

        IllegalStateException ex = assertThrows(IllegalStateException.class, source::version);

        assertTrue(ex.getMessage().contains(METRIC), "must name the metric; was: " + ex.getMessage());
        assertFalse(ex.getMessage().contains(SENTINEL),
                "no failure message may embed the body; was: " + ex.getMessage());
    }

    @Test
    void version_throws_whenTheVersionLabelValueIsEmptyAfterTrim() {
        String body = "blackbox_exporter_build_info{version=\"   \",other=\"" + SENTINEL + "\"} 1\n";
        HttpPrometheusCurrentSource source = source(body, METRIC, DEFAULT_VERSION_LABEL);

        IllegalStateException ex = assertThrows(IllegalStateException.class, source::version);

        assertTrue(ex.getMessage().toLowerCase().contains("empty"),
                "must say the value was empty; was: " + ex.getMessage());
        assertFalse(ex.getMessage().contains(SENTINEL),
                "no failure message may embed the body; was: " + ex.getMessage());
    }

    @Test
    void version_throws_whenTheVersionLabelValueDoesNotParse() {
        String body = "blackbox_exporter_build_info{version=\"not-a-version\",other=\"" + SENTINEL + "\"} 1\n";
        HttpPrometheusCurrentSource source = source(body, METRIC, DEFAULT_VERSION_LABEL);

        IllegalStateException ex = assertThrows(IllegalStateException.class, source::version);

        assertFalse(ex.getMessage().contains(SENTINEL),
                "no failure message may embed the body; was: " + ex.getMessage());
    }

    @Test
    void theFourFailureCasesThisSourceRaises_areAllDistinctFromEachOther() {
        String metricAbsent = failureMessage("some_other{version=\"1.0.0\"} 1\n");
        String labelAbsent = failureMessage("blackbox_exporter_build_info{other=\"x\"} 1\n");
        String emptyAfterTrim = failureMessage("blackbox_exporter_build_info{version=\"   \"} 1\n");
        String unparseable = failureMessage("blackbox_exporter_build_info{version=\"garbage\"} 1\n");

        assertNotEquals(metricAbsent, labelAbsent);
        assertNotEquals(metricAbsent, emptyAfterTrim);
        assertNotEquals(metricAbsent, unparseable);
        assertNotEquals(labelAbsent, emptyAfterTrim);
        assertNotEquals(labelAbsent, unparseable);
        assertNotEquals(emptyAfterTrim, unparseable);
    }

    /**
     * Issue 02's fifth distinct failure message (numbered "the fourth" in ADR-0033's own list,
     * which numbers among the {@code current}-leg failures the non-2xx case owned by
     * {@code PrometheusBodyFetch} separately) must differ from EVERY case above it, in particular
     * from "metric absent" — the two are easy to conflate ("no sample" vs "no MATCHING sample")
     * but lead to different operator fixes: a bad {@code metric} name vs. a bad {@code labels:}
     * selector.
     */
    @Test
    void selectorMatchingNothing_isDistinctFromEveryOtherFailureCase() {
        String metricAbsent = failureMessage("some_other{version=\"1.0.0\"} 1\n");
        String labelAbsent = failureMessage("blackbox_exporter_build_info{other=\"x\"} 1\n");
        String emptyAfterTrim = failureMessage("blackbox_exporter_build_info{version=\"   \"} 1\n");
        String unparseable = failureMessage("blackbox_exporter_build_info{version=\"garbage\"} 1\n");
        String selectorMatchesNothing = selectorFailureMessage(
                "blackbox_exporter_build_info{job=\"blackbox\",version=\"1.0.0\"} 1\n",
                Map.of("job", "does-not-exist"));

        assertNotEquals(metricAbsent, selectorMatchesNothing,
                "'metric absent' and 'selector matched nothing' are different faults with "
                        + "different fixes (bad 'metric' vs. bad 'labels:') and must not share a message");
        assertNotEquals(labelAbsent, selectorMatchesNothing);
        assertNotEquals(emptyAfterTrim, selectorMatchesNothing);
        assertNotEquals(unparseable, selectorMatchesNothing);
    }

    // -----------------------------------------------------------------------
    // Issue 02: the optional `labels:` installation selector — exact string equality on every
    // entry, ANDed, no !=/=~/!~ (ADR-0033). It narrows which samples of `metric` are candidates;
    // first-in-document-order still wins among whatever survives, with no error over a
    // disagreeing later sample (same rule as slice 01, just applied to the narrowed set).
    // -----------------------------------------------------------------------

    @Test
    void version_labelsAbsent_behavesExactlyAsSlice01_everySampleOfTheMetricIsACandidate() {
        String body = """
                blackbox_exporter_build_info{instance="a",version="0.24.0"} 1
                blackbox_exporter_build_info{instance="b",version="0.25.0"} 1
                """;
        HttpPrometheusCurrentSource source = source(body, METRIC, DEFAULT_VERSION_LABEL, Map.of());

        VersionValue result = source.version();

        assertEquals("0.24.0", result.value(),
                "with no 'labels:' configured, every sample of the metric must remain a candidate "
                        + "and first-in-document-order must win, exactly as slice 01 behaved");
    }

    @Test
    void version_selectsTheInstallationMatchingAllSelectorEntries_amongSeveralSamples() {
        // The motivating scenario at the source level: two "installations" of blackbox_exporter
        // behind one endpoint, distinguished by job+pod_name. The selector must pick out the
        // SECOND installation's sample even though it is not first in document order.
        String body = """
                blackbox_exporter_build_info{job="blackbox",pod_name="blackbox-0",version="0.24.0"} 1
                blackbox_exporter_build_info{job="blackbox",pod_name="blackbox-1",version="0.25.0"} 1
                """;
        Map<String, String> selector = Map.of("job", "blackbox", "pod_name", "blackbox-1");
        HttpPrometheusCurrentSource source = source(body, METRIC, DEFAULT_VERSION_LABEL, selector);

        VersionValue result = source.version();

        assertEquals("0.25.0", result.value(),
                "the selector must pick the sample matching ALL entries (job AND pod_name), not "
                        + "the document-order-first sample of the metric");
    }

    @Test
    void version_selectorIsExactStringEquality_notPartial() {
        // Deliberately NOT the class SENTINEL: this is the selector-matched-nothing path, the one
        // message ADR-0033 permits to quote the named metric's label sets, so a sentinel here would
        // legitimately appear and blur what SENTINEL means everywhere else in this class.
        String body = "blackbox_exporter_build_info{job=\"blackbox\",version=\"1.0.0\","
                + "other=\"unremarkable\"} 1\n";
        Map<String, String> selector = Map.of("job", "black");
        HttpPrometheusCurrentSource source = source(body, METRIC, DEFAULT_VERSION_LABEL, selector);

        assertThrows(IllegalStateException.class, source::version,
                "'job: black' must not match a sample whose 'job' is 'blackbox'");
    }

    @Test
    void version_firstDocumentOrderSampleWins_amongMultipleSamplesMatchingTheSelector_withNoError() {
        String body = """
                blackbox_exporter_build_info{job="blackbox",version="0.24.0"} 1
                blackbox_exporter_build_info{job="blackbox",version="0.25.0"} 1
                """;
        Map<String, String> selector = Map.of("job", "blackbox");
        HttpPrometheusCurrentSource source = source(body, METRIC, DEFAULT_VERSION_LABEL, selector);

        VersionValue result = source.version();

        assertEquals("0.24.0", result.value(),
                "among several samples surviving the selector, the FIRST in document order must "
                        + "win, with no error raised over the disagreeing second sample");
    }

    // Escaped-quote label-value matching is owned at the parser level by
    // PrometheusExpositionTests.labelSelector_matchesAgainstTheUnescapedLabelValue_notTheWireEscapedText,
    // where unescaping actually lives; selector-threading through the source is already proven by
    // version_selectsTheInstallationMatchingAllSelectorEntries_amongSeveralSamples above, so no
    // duplicate is kept at this level.

    // -----------------------------------------------------------------------
    // The selector-matches-nothing failure: distinct from "metric absent", naming a BOUNDED
    // number of the label sets actually seen for the metric. This is the sole message in this
    // kind permitted to quote anything from the document — bounded, and only for the named
    // metric. Every other message's body-free guarantee is unaffected (see
    // noFailureMessage_everContainsAnySliceOfTheFetchedBody above, which this must not weaken).
    // -----------------------------------------------------------------------

    @Test
    void version_selectorMatchingNothing_throws_namingTheMetricAndUrl() {
        String body = "blackbox_exporter_build_info{job=\"blackbox\",version=\"1.0.0\"} 1\n";
        Map<String, String> selector = Map.of("job", "does-not-exist");
        HttpPrometheusCurrentSource source = source(body, METRIC, DEFAULT_VERSION_LABEL, selector);

        IllegalStateException ex = assertThrows(IllegalStateException.class, source::version);

        assertTrue(ex.getMessage().contains(METRIC), "must name the metric; was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains(URL), "must name the url; was: " + ex.getMessage());
    }

    @Test
    void version_selectorMatchingNothing_withOnlyOneSampleSeen_stillNamesItsLabelSet() {
        // The single most common misconfiguration: one endpoint, one sample of the metric, a
        // typo'd selector. The operator gets nothing to go on unless this one sample's label set
        // is named — a cap of MAX_LABEL_SETS_NAMED must never round a single sample down to zero.
        String body = "blackbox_exporter_build_info{job=\"blackbox\",pod_name=\"blackbox-0\","
                + "version=\"1.0.0\"} 1\n";
        Map<String, String> selector = Map.of("job", "does-not-exist");
        HttpPrometheusCurrentSource source = source(body, METRIC, DEFAULT_VERSION_LABEL, selector);

        IllegalStateException ex = assertThrows(IllegalStateException.class, source::version);

        assertTrue(ex.getMessage().contains("pod_name=\"blackbox-0\""),
                "with a single sample of the metric seen, its label set must be named so the "
                        + "operator has something to correct the selector against; was: "
                        + ex.getMessage());
    }

    @Test
    void version_selectorMatchingNothing_boundedQuoting_namesSomeOfTheMetricsLabelSets_butIsCappedAndScopedToTheNamedMetric() {
        // 20 samples of the CONFIGURED metric, none matching the selector, each individually
        // identifiable by a unique per-sample marker — proves the message names label sets
        // actually seen for the metric, WITHOUT proving it is exhaustive (it must be capped).
        StringBuilder body = new StringBuilder();
        int sampleCount = 20;
        for (int i = 0; i < sampleCount; i++) {
            body.append("blackbox_exporter_build_info{job=\"blackbox\",instance=\"sample-marker-")
                    .append(i).append("\",version=\"1.0.").append(i).append("\"} 1\n");
        }
        // A sentinel living OUTSIDE any sample of the configured metric: a different metric
        // entirely. The bounded quoting must cover ONLY the named metric's label sets — this
        // string must never appear in the failure message.
        String elsewhereSentinel = "SENTINEL_OTHER_METRIC_NOT_THE_CONFIGURED_ONE_4d2f";
        body.append("some_other_metric{label=\"").append(elsewhereSentinel).append("\"} 1\n");

        Map<String, String> selector = Map.of("job", "does-not-exist");
        HttpPrometheusCurrentSource source = source(body.toString(), METRIC, DEFAULT_VERSION_LABEL, selector);

        IllegalStateException ex = assertThrows(IllegalStateException.class, source::version);
        String message = ex.getMessage();

        assertFalse(message.contains(elsewhereSentinel),
                "the bounded quoting must cover ONLY label sets of the NAMED metric — a sentinel "
                        + "living in a different metric entirely must never appear; was: " + message);

        long markersNamed = java.util.stream.IntStream.range(0, sampleCount)
                .filter(i -> message.contains("sample-marker-" + i))
                .count();
        assertTrue(markersNamed > 0,
                "the message must name at least some of the label sets actually seen for the "
                        + "metric, to let the operator correct the selector; was: " + message);
        assertTrue(markersNamed <= 5,
                "the quoting must be CAPPED at MAX_LABEL_SETS_NAMED (5) — named " + markersNamed
                        + " of the " + sampleCount + " samples seen; was: " + message);
    }

    // -----------------------------------------------------------------------
    // Issue 03: optional `regex` and `strip-prerelease` on the value read out of `version-label`.
    // Both are already implemented elsewhere (RegexVersionExtractor.firstIn,
    // VersionValue.withoutPreRelease) and reused here, not rewritten.
    // -----------------------------------------------------------------------

    private static final String EXTRACTOR_LABEL = "'http-prometheus' current source";

    @Test
    void version_withNoRegexConfigured_theTrimmedLabelValueIsParsedDirectly_slice01Behaviour() {
        String body = "blackbox_exporter_build_info{version=\"  0.25.0  \"} 1\n";
        HttpPrometheusCurrentSource source = source(body, METRIC, DEFAULT_VERSION_LABEL, Optional.empty(), false);

        VersionValue result = source.version();

        assertEquals("0.25.0", result.value());
    }

    @Test
    void version_withRegexConfigured_takesCaptureGroup1OfTheFirstMatch_evenWhenALaterMatchParsesLarger() {
        // Unambiguous pin of first-wins over largest-wins: the label value contains 1.0.0 before
        // 9.9.9. A largest-wins rule would report 9.9.9; ADR-0030's current-leg rule (a current
        // version is an observation, not a selection) requires 1.0.0.
        String body = "blackbox_exporter_build_info{version=\"1.0.0 then later 9.9.9\"} 1\n";
        RegexVersionExtractor extractor =
                new RegexVersionExtractor(EXTRACTOR_LABEL, "(\\d+\\.\\d+\\.\\d+)", SEMVER_PARSER);
        HttpPrometheusCurrentSource source =
                source(body, METRIC, DEFAULT_VERSION_LABEL, Optional.of(extractor), false);

        VersionValue result = source.version();

        assertEquals("1.0.0", result.value(),
                "firstIn must pick the FIRST match (1.0.0), never the largest (9.9.9)");
    }

    @Test
    void version_withRegexConfigured_matchingNothing_throws_withSlice01sDidNotYieldAParseableVersionMessage() {
        // The label value ("1.2.3", no 'v' prefix) is deliberately something the RAW trimmed
        // value WOULD parse successfully on its own — so this test only goes red for the right
        // reason (the regex genuinely not being applied) rather than passing vacuously off the
        // raw-parse fallback path, the way a "no digits in here" fixture would (that fails to
        // parse either way and produces the same message text regardless of whether the regex
        // was ever consulted).
        String body = "blackbox_exporter_build_info{version=\"1.2.3\"} 1\n";
        RegexVersionExtractor extractor =
                new RegexVersionExtractor(EXTRACTOR_LABEL, "^v(\\d+\\.\\d+\\.\\d+)$", SEMVER_PARSER);
        HttpPrometheusCurrentSource source =
                source(body, METRIC, DEFAULT_VERSION_LABEL, Optional.of(extractor), false);

        IllegalStateException ex = assertThrows(IllegalStateException.class, source::version,
                "the configured regex requires a 'v' prefix that '1.2.3' does not have; the "
                        + "regex must actually be consulted rather than falling back to a raw "
                        + "parse of the trimmed value, which would succeed");

        assertTrue(ex.getMessage().contains("did not yield a parseable version"),
                "must carry slice 01's 'did not yield a parseable version' message; was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("1.2.3"),
                "must carry the reason/actual value the regex failed to match a version in; was: "
                        + ex.getMessage());
    }

    @Test
    void version_withStripPrereleaseTrue_clearsThePrereleaseSegment() {
        String body = "blackbox_exporter_build_info{version=\"2.11.1-6b7ecba1\"} 1\n";
        HttpPrometheusCurrentSource source = source(body, METRIC, DEFAULT_VERSION_LABEL, Optional.empty(), true);

        VersionValue result = source.version();

        assertEquals("2.11.1", result.value());
    }

    @Test
    void version_withStripPrereleaseAbsent_preservesThePrereleaseSegment() {
        String body = "blackbox_exporter_build_info{version=\"2.11.1-6b7ecba1\"} 1\n";
        HttpPrometheusCurrentSource source = source(body, METRIC, DEFAULT_VERSION_LABEL, Optional.empty(), false);

        VersionValue result = source.version();

        assertEquals("2.11.1-6b7ecba1", result.value(),
                "with strip-prerelease absent, the prerelease segment must be preserved");
    }

    @Test
    void version_regexAndStripPrerelease_compose_theRegexExtractsThenThePrereleaseIsStripped() {
        String body = "blackbox_exporter_build_info{version=\"prefix-v2.11.1-6b7ecba1-suffix\"} 1\n";
        RegexVersionExtractor extractor =
                new RegexVersionExtractor(EXTRACTOR_LABEL, "v(\\d+\\.\\d+\\.\\d+-[a-z0-9]+)", SEMVER_PARSER);
        HttpPrometheusCurrentSource source =
                source(body, METRIC, DEFAULT_VERSION_LABEL, Optional.of(extractor), true);

        VersionValue result = source.version();

        assertEquals("2.11.1", result.value(),
                "the regex must extract 2.11.1-6b7ecba1 first, then strip-prerelease must clear "
                        + "the prerelease segment; was: " + result.value());
    }

    // -----------------------------------------------------------------------
    // No body content leaks into ANY failure message — a dedicated, explicit sentinel assertion
    // covering every failure path in one place, per ADR-0033's hard requirement.
    // -----------------------------------------------------------------------

    @Test
    void noFailureMessage_everContainsAnySliceOfTheFetchedBody() {
        String metricAbsentBody = "# " + SENTINEL + "\nsome_other_metric{version=\"1.0.0\"} 1\n";
        String labelAbsentBody = "blackbox_exporter_build_info{other=\"" + SENTINEL + "\"} 1\n";
        String emptyBody = "blackbox_exporter_build_info{version=\"   \",other=\"" + SENTINEL + "\"} 1\n";
        String unparseableBody =
                "blackbox_exporter_build_info{version=\"garbage\",other=\"" + SENTINEL + "\"} 1\n";

        for (String body : new String[] {metricAbsentBody, labelAbsentBody, emptyBody, unparseableBody}) {
            String message = failureMessage(body);
            assertFalse(message.contains(SENTINEL),
                    "message must not contain the body's sentinel; body was hidden, message was: " + message);
        }

        // A fifth path reaching the same unparseableVersion helper: a configured regex that
        // matches nothing in the version-label value. The sentinel sits in a NEIGHBOURING label
        // (not the version-label value itself, which the message legitimately names) so this
        // guard catches any widening of the message beyond that single trimmed value.
        String regexMissBody =
                "blackbox_exporter_build_info{version=\"1.2.3\",other=\"" + SENTINEL + "\"} 1\n";
        RegexVersionExtractor nonMatchingExtractor =
                new RegexVersionExtractor(EXTRACTOR_LABEL, "^v(\\d+\\.\\d+\\.\\d+)$", SEMVER_PARSER);
        String regexMissMessage = failureMessage(regexMissBody, Optional.of(nonMatchingExtractor));
        assertFalse(regexMissMessage.contains(SENTINEL),
                "message must not contain the body's sentinel; body was hidden, message was: "
                        + regexMissMessage);
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private static String failureMessage(String body) {
        HttpPrometheusCurrentSource source = source(body, METRIC, DEFAULT_VERSION_LABEL);
        return assertThrows(IllegalStateException.class, source::version).getMessage();
    }

    private static String failureMessage(String body, Optional<RegexVersionExtractor> extractor) {
        HttpPrometheusCurrentSource source = source(body, METRIC, DEFAULT_VERSION_LABEL, extractor, false);
        return assertThrows(IllegalStateException.class, source::version).getMessage();
    }

    private static String selectorFailureMessage(String body, Map<String, String> selector) {
        HttpPrometheusCurrentSource source = source(body, METRIC, DEFAULT_VERSION_LABEL, selector);
        return assertThrows(IllegalStateException.class, source::version).getMessage();
    }

    private static HttpPrometheusCurrentSource source(String body, String metric, String versionLabel) {
        return source(body, metric, versionLabel, Map.of(), Optional.empty(), false);
    }

    private static HttpPrometheusCurrentSource source(
            String body, String metric, String versionLabel, Map<String, String> labels) {
        return source(body, metric, versionLabel, labels, Optional.empty(), false);
    }

    private static HttpPrometheusCurrentSource source(String body, String metric, String versionLabel,
            Optional<RegexVersionExtractor> extractor, boolean stripPrerelease) {
        return source(body, metric, versionLabel, Map.of(), extractor, stripPrerelease);
    }

    /** The single construction site: every other overload above delegates here. */
    private static HttpPrometheusCurrentSource source(String body, String metric, String versionLabel,
            Map<String, String> labels, Optional<RegexVersionExtractor> extractor, boolean stripPrerelease) {
        PrometheusBodyFetch fetch = () -> body;
        return new HttpPrometheusCurrentSource(
                fetch, URL, metric, versionLabel, labels, extractor, stripPrerelease, SEMVER_PARSER);
    }
}
