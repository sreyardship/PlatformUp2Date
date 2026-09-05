package org.yardship.unit.adapters.out.versionsource.current.httpprometheus;

import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.current.httpprometheus.HttpPrometheusCurrentSource;
import org.yardship.adapters.out.versionsource.current.httpprometheus.PrometheusBodyFetch;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import org.yardship.core.domain.primitives.VersionValue;

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
 * selection, {@code version-label} resolution, and the three failure messages this source itself
 * raises (a fourth — non-2xx — belongs to the production {@code PrometheusBodyFetch} and is an
 * integration-level concern). Every failure-message test in this class also asserts that a
 * recognisable sentinel string present in the body never leaks into the thrown message — the hard
 * requirement in ADR-0033 that a routinely-hundreds-of-KB {@code /metrics} body is never echoed
 * back.
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
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private static String failureMessage(String body) {
        HttpPrometheusCurrentSource source = source(body, METRIC, DEFAULT_VERSION_LABEL);
        return assertThrows(IllegalStateException.class, source::version).getMessage();
    }

    private static HttpPrometheusCurrentSource source(String body, String metric, String versionLabel) {
        PrometheusBodyFetch fetch = () -> body;
        return new HttpPrometheusCurrentSource(fetch, URL, metric, versionLabel, SEMVER_PARSER);
    }
}
