package org.yardship.unit.validation;

import org.junit.jupiter.api.Test;
import org.yardship.confcheck.outcome.MetricResult;
import org.yardship.confcheck.outcome.ValidationOutcome;
import org.yardship.confcheck.validation.MetricExtractionValidation;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MetricExtractionValidation}, the use case behind the {@code metric}
 * subcommand. These pin the extraction contract at the cheapest possible seam: a raw Prometheus
 * text-exposition body in, {@link ValidationOutcome} out — no HTTP, no CLI wiring, no file I/O.
 *
 * <p>Extraction semantics MUST agree with the shipped {@code http-prometheus} current source
 * ({@code HttpPrometheusCurrentSource}, slices 01-03, and its parser {@code PrometheusExposition})
 * exactly — see {@code docs/adr/0033-http-prometheus-current-source.md}: exact metric-name
 * matching, an exact-match/ANDed {@code --label} selector, the FIRST surviving sample in document
 * order, {@code version-label} lookup (trimmed), an optional {@code regex}'s first-PARSEABLE-match
 * extraction (never largest), and {@code strip-prerelease} applied last, to the parsed value. A
 * validator that disagrees with the source it validates is worse than no validator: no case here
 * may report OK where the runtime source would fail a scrape, or vice versa.
 */
class MetricExtractionValidationTests {

    private final MetricExtractionValidation validation = new MetricExtractionValidation();

    private static final VersionParser SEMVER = new VersionParser(VersionScheme.SEMVER);
    private static final String METRIC = "blackbox_exporter_build_info";

    private static final String BLACKBOX_BODY = """
            # HELP blackbox_exporter_build_info A metric with a constant '1' value labeled by version, revision, branch.
            # TYPE blackbox_exporter_build_info gauge
            blackbox_exporter_build_info{branch="HEAD",goversion="go1.22.4",revision="0ec2a6b",version="0.25.0"} 1
            """;

    // --- No scheme, no regex: extraction-only ----------------------------------------------------

    @Test
    void blackboxShapedFixture_noScheme_isOkExtractionOnly_versionLabelDefaultsToVersion() {
        ValidationOutcome outcome = validation.validate(
                BLACKBOX_BODY, METRIC, Map.of(), "version", Optional.empty(), false, Optional.empty());

        ValidationOutcome.MetricOk ok = assertInstanceOf(ValidationOutcome.MetricOk.class, outcome);
        assertEquals(ValidationOutcome.MetricOk.EXIT_CODE, ok.exitCode());
        MetricResult result = ok.result();
        assertEquals("0.25.0", result.rawText().orElseThrow());
        assertFalse(result.schemeRequested());
        assertEquals(1, result.matchedSampleCount());
        assertEquals("0.25.0", result.takenLabels().get("version"));
    }

    @Test
    void withScheme_valueParses_isOkWithParsedVersion() {
        ValidationOutcome outcome = validation.validate(
                BLACKBOX_BODY, METRIC, Map.of(), "version", Optional.empty(), false, Optional.of(SEMVER));

        ValidationOutcome.MetricOk ok = assertInstanceOf(ValidationOutcome.MetricOk.class, outcome);
        MetricResult result = ok.result();
        assertTrue(result.schemeRequested());
        assertEquals("0.25.0", result.parsed().orElseThrow().value());
    }

    // --- --version-label honoured, defaults to "version" -----------------------------------------

    @Test
    void versionLabel_customName_isHonoured() {
        String body = "some_metric{app_version=\"3.4.5\"} 1\n";

        ValidationOutcome outcome = validation.validate(
                body, "some_metric", Map.of(), "app_version", Optional.empty(), false, Optional.empty());

        ValidationOutcome.MetricOk ok = assertInstanceOf(ValidationOutcome.MetricOk.class, outcome);
        assertEquals("3.4.5", ok.result().rawText().orElseThrow());
    }

    // --- Metric absent entirely ---------------------------------------------------------------

    @Test
    void metricAbsentFromBody_isValidButEmpty_matchedSampleCountZero() {
        ValidationOutcome outcome = validation.validate(
                BLACKBOX_BODY, "no_such_metric", Map.of(), "version", Optional.empty(), false, Optional.empty());

        ValidationOutcome.MetricValidButEmpty empty =
                assertInstanceOf(ValidationOutcome.MetricValidButEmpty.class, outcome);
        assertEquals(ValidationOutcome.MetricValidButEmpty.EXIT_CODE, empty.exitCode());
        assertEquals(0, empty.result().matchedSampleCount());
    }

    // --- Selector matched nothing: reported distinctly from an absent metric ---------------------

    @Test
    void selectorMatchedNothing_isReportedDistinctlyFromMetricAbsent_namingLabelSetsSeen() {
        String body = """
                blackbox_exporter_build_info{job="blackbox",version="1.0.0"} 1
                blackbox_exporter_build_info{job="other",version="2.0.0"} 1
                """;
        Map<String, String> selector = Map.of("job", "does-not-exist");

        ValidationOutcome absentOutcome = validation.validate(
                BLACKBOX_BODY, "no_such_metric", Map.of(), "version", Optional.empty(), false, Optional.empty());
        ValidationOutcome selectorOutcome = validation.validate(
                body, METRIC, selector, "version", Optional.empty(), false, Optional.empty());

        ValidationOutcome.MetricValidButEmpty absentEmpty =
                assertInstanceOf(ValidationOutcome.MetricValidButEmpty.class, absentOutcome);
        ValidationOutcome.MetricValidButEmpty selectorEmpty =
                assertInstanceOf(ValidationOutcome.MetricValidButEmpty.class, selectorOutcome);

        assertFalse(selectorEmpty.message().equals(absentEmpty.message()),
                "a selector matching nothing must be reported with a different message than an absent metric");
        assertTrue(selectorEmpty.message().contains("job=\"blackbox\"") || selectorEmpty.message().contains("blackbox"),
                "the message must name (a bounded number of) the label sets actually seen for the metric");
        assertTrue(selectorEmpty.message().contains("job=\"other\"") || selectorEmpty.message().contains("other"),
                "the message must name (a bounded number of) the label sets actually seen for the metric");
    }

    @Test
    void selectorMatchedNothing_namedLabelSetsAreBounded() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            body.append(METRIC).append("{instance=\"i").append(i).append("\",version=\"1.0.").append(i).append("\"} 1\n");
        }
        Map<String, String> selector = Map.of("instance", "does-not-exist");

        ValidationOutcome outcome = validation.validate(
                body.toString(), METRIC, selector, "version", Optional.empty(), false, Optional.empty());

        ValidationOutcome.MetricValidButEmpty empty =
                assertInstanceOf(ValidationOutcome.MetricValidButEmpty.class, outcome);
        long namedCount = java.util.regex.Pattern.compile("instance=\"i\\d+\"").matcher(empty.message()).results().count();
        assertTrue(namedCount > 0, "at least one label set must be named");
        assertTrue(namedCount <= 5, "the number of label sets named must be bounded (at most 5); found: " + namedCount);
    }

    // --- --label repeatable, ANDs, exact match -----------------------------------------------------

    @Test
    void labelSelector_ANDsEveryEntry_exactMatch() {
        String body = """
                blackbox_exporter_build_info{job="blackbox",pod_name="blackbox-0",version="1.0.0"} 1
                blackbox_exporter_build_info{job="blackbox",pod_name="blackbox-1",version="2.0.0"} 1
                """;
        Map<String, String> selector = Map.of("job", "blackbox", "pod_name", "blackbox-1");

        ValidationOutcome outcome = validation.validate(
                body, METRIC, selector, "version", Optional.empty(), false, Optional.empty());

        ValidationOutcome.MetricOk ok = assertInstanceOf(ValidationOutcome.MetricOk.class, outcome);
        assertEquals("2.0.0", ok.result().rawText().orElseThrow());
    }

    @Test
    void labelSelector_isExactMatch_notSubstringOrPrefix() {
        String body = "blackbox_exporter_build_info{job=\"blackbox\",version=\"1.0.0\"} 1\n";
        Map<String, String> selector = Map.of("job", "black");

        ValidationOutcome outcome = validation.validate(
                body, METRIC, selector, "version", Optional.empty(), false, Optional.empty());

        assertInstanceOf(ValidationOutcome.MetricValidButEmpty.class, outcome);
    }

    // --- version-label absent from the matched sample ----------------------------------------------

    @Test
    void versionLabelAbsentFromMatchedSample_isReportedDistinctly() {
        String body = "blackbox_exporter_build_info{job=\"blackbox\"} 1\n";

        ValidationOutcome outcome = validation.validate(
                body, METRIC, Map.of(), "version", Optional.empty(), false, Optional.empty());

        ValidationOutcome.MetricValidButEmpty empty =
                assertInstanceOf(ValidationOutcome.MetricValidButEmpty.class, outcome);
        assertEquals(1, empty.result().matchedSampleCount());
        assertTrue(empty.result().rawText().isEmpty());
    }

    @Test
    void versionLabelPresentButEmptyAfterTrim_isReportedDistinctly() {
        String body = "blackbox_exporter_build_info{version=\"   \"} 1\n";

        ValidationOutcome outcome = validation.validate(
                body, METRIC, Map.of(), "version", Optional.empty(), false, Optional.empty());

        assertInstanceOf(ValidationOutcome.MetricValidButEmpty.class, outcome);
    }

    // --- Several matching samples: reported as such, naming the one taken -------------------------

    @Test
    void severalMatchingSamples_reportsAmbiguity_takesTheFirstInDocumentOrder() {
        String body = """
                blackbox_exporter_build_info{instance="a",version="0.24.0"} 1
                blackbox_exporter_build_info{instance="b",version="0.25.0"} 1
                """;

        ValidationOutcome outcome = validation.validate(
                body, METRIC, Map.of(), "version", Optional.empty(), false, Optional.empty());

        ValidationOutcome.MetricOk ok = assertInstanceOf(ValidationOutcome.MetricOk.class, outcome);
        MetricResult result = ok.result();
        assertEquals(2, result.matchedSampleCount(), "both samples matched the metric/selector");
        assertEquals("0.24.0", result.rawText().orElseThrow(), "the FIRST sample in document order must be taken");
        assertEquals("a", result.takenLabels().get("instance"), "the taken sample's own labels must be reported");
    }

    @Test
    void exactlyOneMatchingSample_matchedSampleCountIsOne() {
        ValidationOutcome outcome = validation.validate(
                BLACKBOX_BODY, METRIC, Map.of(), "version", Optional.empty(), false, Optional.empty());

        ValidationOutcome.MetricOk ok = assertInstanceOf(ValidationOutcome.MetricOk.class, outcome);
        assertEquals(1, ok.result().matchedSampleCount());
    }

    // --- --regex: first-parseable-match group-1 extraction ------------------------------------------

    @Test
    void regex_noncompilingPattern_isReportedAsConfigInvalid_notThrown() {
        ValidationOutcome outcome = validation.validate(
                BLACKBOX_BODY, METRIC, Map.of(), "version", Optional.of("(unclosed"), false, Optional.of(SEMVER));

        assertInstanceOf(ValidationOutcome.ConfigInvalid.class, outcome);
    }

    @Test
    void regex_withScheme_firstParseableMatchWins_neverTheLargest() {
        String body = "my_metric{version=\"1.0.0 then later 9.9.9\"} 1\n";

        ValidationOutcome outcome = validation.validate(
                body, "my_metric", Map.of(), "version",
                Optional.of("(\\d+\\.\\d+\\.\\d+)"), false, Optional.of(SEMVER));

        ValidationOutcome.MetricOk ok = assertInstanceOf(ValidationOutcome.MetricOk.class, outcome);
        assertEquals("1.0.0", ok.result().parsed().orElseThrow().value());
    }

    @Test
    void regex_noScheme_extractionOnly_takesFirstCandidateRegardlessOfParseability() {
        String body = "my_metric{version=\"prefix-42-suffix\"} 1\n";

        ValidationOutcome outcome = validation.validate(
                body, "my_metric", Map.of(), "version", Optional.of("(\\d+)"), false, Optional.empty());

        ValidationOutcome.MetricOk ok = assertInstanceOf(ValidationOutcome.MetricOk.class, outcome);
        assertEquals("42", ok.result().rawText().orElseThrow());
    }

    @Test
    void regex_noParseableMatch_isValidButEmpty() {
        String body = "my_metric{version=\"no digits here\"} 1\n";

        ValidationOutcome outcome = validation.validate(
                body, "my_metric", Map.of(), "version", Optional.of("(\\d+)"), false, Optional.of(SEMVER));

        assertInstanceOf(ValidationOutcome.MetricValidButEmpty.class, outcome);
    }

    // --- --strip-prerelease matches the backend's semantics: applied to the parsed value, last ------

    @Test
    void stripPrerelease_appliedToTheParsedValue() {
        String body = "my_metric{version=\"1.2.3-rc1\"} 1\n";

        ValidationOutcome outcome = validation.validate(
                body, "my_metric", Map.of(), "version", Optional.empty(), true, Optional.of(SEMVER));

        ValidationOutcome.MetricOk ok = assertInstanceOf(ValidationOutcome.MetricOk.class, outcome);
        MetricResult result = ok.result();
        assertTrue(result.strippedPreRelease());
        assertEquals("1.2.3", result.parsed().orElseThrow().value());
        assertEquals("1.2.3-rc1", result.rawText().orElseThrow(),
                "the raw extracted text must remain unmodified; only the parsed value is stripped");
    }
}
