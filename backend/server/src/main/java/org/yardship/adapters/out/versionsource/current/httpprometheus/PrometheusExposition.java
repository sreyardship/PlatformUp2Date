package org.yardship.adapters.out.versionsource.current.httpprometheus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * A pure, HTTP-free, version-free parser of the Prometheus text-exposition format, sitting beside
 * {@link HttpPrometheusCurrentSource} exactly as {@code OsReleaseParser} sits beside
 * {@code SshOsReleaseCurrentSource}. It performs no I/O and holds no state: body text and a metric
 * name in, matching samples' label maps out, in document order.
 *
 * <p>It does <b>not</b> parse sample values, timestamps, metric types, histograms or summaries —
 * this kind never needs them (see {@code docs/adr/0033-http-prometheus-current-source.md}).
 *
 * <p>The grammar handled is deliberately small:
 * <ul>
 *   <li>a line starting with {@code #} is a comment and is skipped — this covers {@code # HELP},
 *       {@code # TYPE} and OpenMetrics' {@code # EOF} terminator for free;</li>
 *   <li>the metric name is matched <b>exactly</b>, parsed up to the first {@code {} or whitespace,
 *       so a configured {@code foo_build_info} never matches {@code foo_build_info_extra};</li>
 *   <li>a label set, when present, is parsed between {@code {} and the matching {@code }},
 *       unescaping the three defined escape sequences in a label value: {@code \\}, {@code \"} and
 *       {@code \n};</li>
 *   <li>a duplicate label name within one label set resolves <b>first-wins</b>, consistent with
 *       document-order-first-wins everywhere else in this kind;</li>
 *   <li>everything after the closing {@code }} (the sample value, an optional timestamp, an
 *       optional OpenMetrics exemplar) is ignored;</li>
 *   <li>a sample line with no label set at all (e.g. {@code some_metric 1}) yields an empty label
 *       map, not a missing sample.</li>
 * </ul>
 *
 * <p><b>Malformed-input policy:</b> a sample line that fails to parse — an unterminated label set,
 * or a label value that is not a quoted string — is <b>skipped whole</b>, never thrown on.
 * {@link #samplesOf(String, String)} never throws for any input; a single truncated or malformed
 * line (a truncating proxy, a stray partial line) simply drops out of the result, and parsing
 * continues with the next line. This is deliberate: a malformed line late in an 800-line body must
 * never prevent an earlier well-formed sample from being returned, and it removes a body-leak path
 * entirely — a partially-parsed line is never surfaced, even as an empty-label sample.
 */
public class PrometheusExposition {

    /**
     * Returns the label maps of every sample of {@code metricName} in {@code body}, in document
     * order. Never throws: a malformed sample line is skipped rather than reported (see class
     * javadoc).
     *
     * @param body       raw Prometheus (or OpenMetrics) text-exposition body
     * @param metricName the exact metric name to match
     * @return matching samples, in document order; empty when none match
     */
    public List<PrometheusSample> samplesOf(String body, String metricName) {
        List<PrometheusSample> samples = new ArrayList<>();
        for (String line : linesOf(body)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            matchingSample(trimmed, metricName).ifPresent(samples::add);
        }
        return samples;
    }

    /**
     * Counts the non-comment, non-blank lines in {@code body} — i.e. every line that was a
     * candidate sample line, whether or not it matched a metric name or parsed cleanly. Exists
     * solely so a "metric not found" message can distinguish an empty or comment-only body from
     * one that returned metrics, just not the configured one — a count only, never a quote of the
     * lines themselves.
     *
     * @param body raw Prometheus (or OpenMetrics) text-exposition body
     * @return the number of non-comment, non-blank lines in {@code body}
     */
    public long sampleLineCountIn(String body) {
        return linesOf(body).stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .count();
    }

    /**
     * Splits {@code body} into lines the one way both {@link #samplesOf(String, String)} and
     * {@link #sampleLineCountIn(String)} agree on: on {@code \n} only, so a lone {@code \r} or a
     * {@code \r\n} pair is never treated as a line break here (unlike {@link String#lines()},
     * which splits on both). Using one helper for both methods keeps what is counted and what is
     * parsed in lockstep for every body, {@code \r}-separated ones included.
     */
    private List<String> linesOf(String body) {
        return List.of(body.split("\n", -1));
    }

    private Optional<PrometheusSample> matchingSample(String line, String metricName) {
        int nameEnd = nameEndIndex(line);
        String name = line.substring(0, nameEnd);
        if (!name.equals(metricName)) {
            return Optional.empty();
        }
        if (nameEnd >= line.length() || line.charAt(nameEnd) != '{') {
            return Optional.of(new PrometheusSample(Map.of()));
        }
        OptionalInt closingBrace = findClosingBrace(line, nameEnd);
        if (closingBrace.isEmpty()) {
            return Optional.empty();
        }
        Optional<Map<String, String>> labels = parseLabels(line.substring(nameEnd + 1, closingBrace.getAsInt()));
        return labels.map(PrometheusSample::new);
    }

    /**
     * Finds the index at which the metric name ends: the first {@code {} or whitespace character.
     */
    private int nameEndIndex(String line) {
        int index = 0;
        while (index < line.length()) {
            char c = line.charAt(index);
            if (c == '{' || Character.isWhitespace(c)) {
                break;
            }
            index++;
        }
        return index;
    }

    /**
     * Finds the index of the {@code }} that closes the label set opened at {@code openBraceIndex},
     * respecting quoted label values so a {@code }} inside a value never terminates the set early.
     * Returns empty when the line has no such closing brace at all — an unterminated label set,
     * which per the class's malformed-input policy is skipped, never thrown on.
     */
    private OptionalInt findClosingBrace(String line, int openBraceIndex) {
        boolean inQuotes = false;
        boolean escaped = false;
        for (int i = openBraceIndex + 1; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inQuotes = !inQuotes;
                continue;
            }
            if (c == '}' && !inQuotes) {
                return OptionalInt.of(i);
            }
        }
        return OptionalInt.empty();
    }

    /**
     * Parses a comma-separated {@code name="value"} label set, unescaping {@code \\}, {@code \"}
     * and {@code \n} in each value. Splitting is done by hand, respecting quotes, rather than by a
     * naive split on {@code "} or {@code ,} — either of which would misparse a value containing an
     * escaped quote or a literal comma-shaped substring.
     *
     * <p>Returns empty when the label set is malformed — e.g. a value that is not a quoted string
     * — rather than the labels parsed so far. Per the class's malformed-input policy, a malformed
     * line is skipped whole; it must never surface as a sample with a partial (or empty) label map.
     *
     * <p>A duplicate label name resolves first-wins ({@link Map#putIfAbsent}), consistent with
     * document-order-first-wins everywhere else in this kind.
     */
    private Optional<Map<String, String>> parseLabels(String labelSet) {
        Map<String, String> labels = new LinkedHashMap<>();
        int index = 0;
        int length = labelSet.length();
        while (index < length) {
            while (index < length && (labelSet.charAt(index) == ',' || Character.isWhitespace(labelSet.charAt(index)))) {
                index++;
            }
            if (index >= length) {
                break;
            }
            int equalsIndex = labelSet.indexOf('=', index);
            if (equalsIndex < 0) {
                return Optional.empty();
            }
            String labelName = labelSet.substring(index, equalsIndex).strip();
            int valueStart = equalsIndex + 1;
            if (valueStart >= length || labelSet.charAt(valueStart) != '"') {
                return Optional.empty();
            }
            StringBuilder rawValue = new StringBuilder();
            int i = valueStart + 1;
            boolean escaped = false;
            while (i < length) {
                char c = labelSet.charAt(i);
                if (escaped) {
                    rawValue.append(unescape(c));
                    escaped = false;
                    i++;
                    continue;
                }
                if (c == '\\') {
                    escaped = true;
                    i++;
                    continue;
                }
                if (c == '"') {
                    break;
                }
                rawValue.append(c);
                i++;
            }
            labels.putIfAbsent(labelName, rawValue.toString());
            index = i + 1;
        }
        return Optional.of(labels);
    }

    private char unescape(char escaped) {
        return switch (escaped) {
            case 'n' -> '\n';
            case '"' -> '"';
            case '\\' -> '\\';
            default -> escaped;
        };
    }
}
