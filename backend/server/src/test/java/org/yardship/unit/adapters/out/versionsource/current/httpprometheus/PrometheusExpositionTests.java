package org.yardship.unit.adapters.out.versionsource.current.httpprometheus;

import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.current.httpprometheus.PrometheusExposition;
import org.yardship.adapters.out.versionsource.current.httpprometheus.PrometheusSample;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PrometheusExposition} — the pure, HTTP-free, version-free Prometheus
 * text-exposition parser sitting beside {@code HttpPrometheusCurrentSource} exactly as
 * {@code OsReleaseParser} sits beside {@code SshOsReleaseCurrentSource}. No Arc, no network: every
 * test is a pure-function call over a raw exposition-format string.
 *
 * <p>{@code docs/adr/0033-http-prometheus-current-source.md} is the binding specification. This
 * class owns the FULL parsing grammar: comment skipping (including OpenMetrics' {@code # EOF}),
 * exact metric-name matching, label-value unescaping, content-after-{@code }} truncation, the
 * bare-sample (no label set) case, and document-order preservation.
 *
 * <p>Public API under test: {@link PrometheusExposition#samplesOf(String, String)} — body text and
 * a metric name in, matching samples' label maps out, in document order. It does not parse sample
 * values, timestamps, metric types, histograms, or summaries; none of those are asserted here
 * because none of them exist in its return type.
 */
class PrometheusExpositionTests {

    private final PrometheusExposition exposition = new PrometheusExposition();

    // -----------------------------------------------------------------------
    // Comment lines are skipped, including OpenMetrics' "# EOF"
    // -----------------------------------------------------------------------

    @Test
    void helpAndTypeCommentLines_areSkipped() {
        String body = """
                # HELP blackbox_exporter_build_info A metric with a constant '1' value labeled by version, revision, branch.
                # TYPE blackbox_exporter_build_info gauge
                blackbox_exporter_build_info{version="0.25.0"} 1
                """;

        List<PrometheusSample> result = exposition.samplesOf(body, "blackbox_exporter_build_info");

        assertEquals(1, result.size());
        assertEquals("0.25.0", result.get(0).labels().get("version"));
    }

    @Test
    void openMetricsEofTerminator_isSkipped_asJustAnotherCommentLine() {
        String body = """
                blackbox_exporter_build_info{version="0.25.0"} 1
                # EOF
                """;

        List<PrometheusSample> result = exposition.samplesOf(body, "blackbox_exporter_build_info");

        assertEquals(1, result.size());
        assertEquals("0.25.0", result.get(0).labels().get("version"));
    }

    @Test
    void aBodyTerminatedWithHashEof_parsesIdenticallyToOneWithout() {
        String withoutEof = """
                # HELP blackbox_exporter_build_info help text
                # TYPE blackbox_exporter_build_info gauge
                blackbox_exporter_build_info{version="0.25.0",instance="a"} 1
                """;
        String withEof = withoutEof + "# EOF\n";

        List<PrometheusSample> resultWithout = exposition.samplesOf(withoutEof, "blackbox_exporter_build_info");
        List<PrometheusSample> resultWith = exposition.samplesOf(withEof, "blackbox_exporter_build_info");

        assertEquals(resultWithout, resultWith,
                "a body terminated with '# EOF' must parse identically to one without it");
    }

    // -----------------------------------------------------------------------
    // Exact metric-name matching
    // -----------------------------------------------------------------------

    @Test
    void metricName_mustMatchExactly_aLongerMetricSharingItAsAPrefixIsNotMatched() {
        String body = """
                foo_build_info{version="1.0.0"} 1
                foo_build_info_extra{version="9.9.9"} 1
                """;

        List<PrometheusSample> result = exposition.samplesOf(body, "foo_build_info");

        assertEquals(1, result.size(),
                "'foo_build_info' must not match 'foo_build_info_extra'; found: " + result);
        assertEquals("1.0.0", result.get(0).labels().get("version"));
    }

    @Test
    void metricName_withNoLabelSetAtAll_isMatchedUpToWhitespace() {
        String body = """
                foo_build_info_extra{version="9.9.9"} 1
                foo_build_info 1
                """;

        List<PrometheusSample> result = exposition.samplesOf(body, "foo_build_info");

        assertEquals(1, result.size(),
                "the bare 'foo_build_info 1' line must match on the metric name up to whitespace, "
                        + "and 'foo_build_info_extra' must still not match; found: " + result);
    }

    // -----------------------------------------------------------------------
    // A bare "name value" line with no label set -> empty label map
    // -----------------------------------------------------------------------

    @Test
    void aBareSampleLine_withNoLabelSet_yieldsAnEmptyLabelMap() {
        String body = "some_metric 1\n";

        List<PrometheusSample> result = exposition.samplesOf(body, "some_metric");

        assertEquals(1, result.size());
        assertTrue(result.get(0).labels().isEmpty(),
                "a sample with no label set must yield an empty label map, not be dropped");
    }

    // -----------------------------------------------------------------------
    // Label-value unescaping: \\, \", \n
    // -----------------------------------------------------------------------

    @Test
    void labelValue_unescapesEscapedNewline() {
        // Wire text: my_metric{a="line1\nline2"} 1  (the "\n" here is the two-character escape
        // sequence backslash-n, exactly as it appears on the wire, NOT a real newline byte).
        String body = "my_metric{a=\"line1\\nline2\"} 1\n";

        List<PrometheusSample> result = exposition.samplesOf(body, "my_metric");

        assertEquals(1, result.size());
        assertEquals("line1\nline2", result.get(0).labels().get("a"),
                "the two-character '\\n' escape sequence must unescape to a real newline character");
    }

    @Test
    void labelValue_unescapesEscapedBackslash() {
        // Wire text: my_metric{a="back\\slash"} 1  (two backslash characters on the wire must
        // unescape to exactly one backslash character in the parsed value).
        String body = "my_metric{a=\"back\\\\slash\"} 1\n";

        List<PrometheusSample> result = exposition.samplesOf(body, "my_metric");

        assertEquals(1, result.size());
        assertEquals("back\\slash", result.get(0).labels().get("a"),
                "the two-character '\\\\' escape sequence must unescape to a single backslash character");
    }

    @Test
    void labelValue_unescapesEscapedQuote_andDoesNotCorruptANeighbouringLabel() {
        // Wire text: my_metric{a="say \"hi\"",b="next"} 1
        // A naive split on '"' would misparse this and corrupt 'b'. The escaping is load-bearing
        // specifically because it protects the NEIGHBOURING label, not because any real version
        // label carries a quote.
        String body = "my_metric{a=\"say \\\"hi\\\"\",b=\"next\"} 1\n";

        List<PrometheusSample> result = exposition.samplesOf(body, "my_metric");

        assertEquals(1, result.size());
        Map<String, String> labels = result.get(0).labels();
        assertEquals("say \"hi\"", labels.get("a"),
                "the escaped quotes inside 'a' must unescape to literal quote characters");
        assertEquals("next", labels.get("b"),
                "a naive split-on-quote would corrupt this neighbouring label; it must read 'next' exactly");
    }

    // -----------------------------------------------------------------------
    // Content after the closing '}' is ignored, including an OpenMetrics exemplar
    // -----------------------------------------------------------------------

    @Test
    void contentAfterTheClosingBrace_isIgnored() {
        String body = "my_metric{version=\"1.2.3\"} 1 1620000000\n";

        List<PrometheusSample> result = exposition.samplesOf(body, "my_metric");

        assertEquals(1, result.size());
        assertEquals("1.2.3", result.get(0).labels().get("version"));
        assertEquals(1, result.get(0).labels().size(),
                "only the label set inside the braces must be parsed; nothing after '}' should "
                        + "contribute additional labels");
    }

    @Test
    void anOpenMetricsExemplarAfterTheClosingBrace_isIgnored() {
        // OpenMetrics exemplars are appended after the sample value: # {trace_id="..."} value timestamp
        String body = "my_metric{version=\"1.2.3\"} 1 # {trace_id=\"abc123\"} 1.0 1620000000\n";

        List<PrometheusSample> result = exposition.samplesOf(body, "my_metric");

        assertEquals(1, result.size());
        assertEquals("1.2.3", result.get(0).labels().get("version"));
        assertFalse(result.get(0).labels().containsKey("trace_id"),
                "an OpenMetrics exemplar living after the sample value must never contribute a label");
    }

    // -----------------------------------------------------------------------
    // Document order is preserved
    // -----------------------------------------------------------------------

    @Test
    void multipleMatchingSamples_arePreservedInDocumentOrder() {
        String body = """
                blackbox_exporter_build_info{instance="a",version="0.24.0"} 1
                some_other_metric{version="8.8.8"} 1
                blackbox_exporter_build_info{instance="b",version="0.25.0"} 1
                blackbox_exporter_build_info{instance="c",version="0.26.0"} 1
                """;

        List<PrometheusSample> result = exposition.samplesOf(body, "blackbox_exporter_build_info");

        assertEquals(3, result.size());
        assertEquals("0.24.0", result.get(0).labels().get("version"));
        assertEquals("0.25.0", result.get(1).labels().get("version"));
        assertEquals("0.26.0", result.get(2).labels().get("version"));
    }

    // -----------------------------------------------------------------------
    // Malformed sample lines are SKIPPED, never thrown on — parsing continues with the next line.
    // samplesOf must not throw on malformed input at all: a single truncated line late in an
    // 800-line body must not kill a read that an earlier (or later) well-formed sample would have
    // satisfied. Each test embeds a recognisable sentinel in the malformed line's own label
    // value(s) and asserts it never leaks into any returned label value — this is exactly the kind
    // of leak a body-embedding failure mode would produce.
    // -----------------------------------------------------------------------

    @Test
    void malformedLine_unterminatedLabelSet_isSkipped_notThrown_laterWellFormedSampleStillReturned() {
        String sentinel = "SENTINEL_UNTERMINATED_7f3c1a9d";
        String body = "foo{version=\"" + sentinel + "\" 1\n"
                + "foo{version=\"1.0.0\"} 1\n";

        List<PrometheusSample> result = assertDoesNotThrow(() -> exposition.samplesOf(body, "foo"),
                "an unterminated label set must be skipped, never thrown on");

        assertEquals(1, result.size(),
                "the malformed (unterminated) first line must be skipped entirely; only the "
                        + "well-formed second sample must be returned; found: " + result);
        assertEquals("1.0.0", result.get(0).labels().get("version"));
        for (PrometheusSample sample : result) {
            assertFalse(sample.labels().containsValue(sentinel),
                    "the sentinel from the skipped malformed line must never leak into a returned "
                            + "label value");
        }
    }

    @Test
    void malformedLine_unquotedLabelValue_isSkipped_notThrown() {
        String sentinel = "SENTINEL_UNQUOTED_2b9d4e6f";
        String body = "foo{a=1,version=\"" + sentinel + "\"} 1\n"
                + "foo{version=\"1.0.0\"} 1\n";

        List<PrometheusSample> result = assertDoesNotThrow(() -> exposition.samplesOf(body, "foo"),
                "an unquoted label value must be skipped, never thrown on");

        assertEquals(1, result.size(),
                "the malformed (unquoted label value) first line must be skipped entirely; only "
                        + "the well-formed second sample must be returned; found: " + result);
        assertEquals("1.0.0", result.get(0).labels().get("version"));
        for (PrometheusSample sample : result) {
            assertFalse(sample.labels().containsValue(sentinel),
                    "the sentinel from the skipped malformed line must never leak into a returned "
                            + "label value");
        }
    }

    // -----------------------------------------------------------------------
    // Grammar edge cases: empty label set, a brace inside a value, a comma inside a value.
    // -----------------------------------------------------------------------

    @Test
    void emptyLabelSet_yieldsAnEmptyLabelMap_sampleStillMatched() {
        String body = "foo{} 1\n";

        List<PrometheusSample> result = exposition.samplesOf(body, "foo");

        assertEquals(1, result.size());
        assertTrue(result.get(0).labels().isEmpty(),
                "an empty label set must yield an empty label map, not be dropped");
    }

    @Test
    void labelValueContainingABrace_doesNotTerminateTheLabelSetEarly() {
        // Exercises findClosingBrace's quote-awareness: a naive indexOf('}') would stop at the
        // brace inside "x}y" and pass every existing test.
        String body = "foo{a=\"x}y\",version=\"1.0.0\"} 1\n";

        List<PrometheusSample> result = exposition.samplesOf(body, "foo");

        assertEquals(1, result.size());
        assertEquals("x}y", result.get(0).labels().get("a"));
        assertEquals("1.0.0", result.get(0).labels().get("version"));
    }

    @Test
    void labelValueContainingAComma_doesNotSplitTheLabelSetEarly() {
        // Exercises parseLabels' quote-aware splitting: a naive split on ',' would break "x,y"
        // into two bogus label fragments.
        String body = "foo{a=\"x,y\",version=\"1.0.0\"} 1\n";

        List<PrometheusSample> result = exposition.samplesOf(body, "foo");

        assertEquals(1, result.size());
        assertEquals("x,y", result.get(0).labels().get("a"));
        assertEquals("1.0.0", result.get(0).labels().get("version"));
    }

    // -----------------------------------------------------------------------
    // Duplicate label names resolve first-wins, consistent with document-order-first-wins
    // everywhere else in this kind (the first sample of a metric, the first matching header, ...).
    // -----------------------------------------------------------------------

    @Test
    void duplicateLabelNames_resolveFirstWins() {
        String body = "foo{version=\"1.0.0\",version=\"2.0.0\"} 1\n";

        List<PrometheusSample> result = exposition.samplesOf(body, "foo");

        assertEquals(1, result.size());
        assertEquals("1.0.0", result.get(0).labels().get("version"),
                "duplicate label names must resolve first-wins, consistent with "
                        + "document-order-first-wins elsewhere in this kind");
    }
}
