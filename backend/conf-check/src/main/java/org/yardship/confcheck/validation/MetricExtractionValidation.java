package org.yardship.confcheck.validation;

import org.yardship.confcheck.metric.PrometheusExposition;
import org.yardship.confcheck.metric.PrometheusSample;
import org.yardship.confcheck.outcome.MetricResult;
import org.yardship.confcheck.outcome.ValidationOutcome;
import org.yardship.core.domain.exceptions.InvalidVersionException;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionPattern;
import org.yardship.core.domain.primitives.VersionValue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Validates an {@code http-prometheus} current source's {@code metric} / {@code --label} selector
 * / {@code version-label} (+ optional {@code regex}) against a raw Prometheus text-exposition
 * body: parses the body via {@link PrometheusExposition} (this module's documented hand copy of
 * the backend parser — see that class's javadoc for the parser-sharing decision), narrows to
 * samples matching every {@code --label} entry (exact string equality, ANDed), takes the FIRST
 * surviving sample in document order, reads {@code version-label} off it, and — when a
 * {@link VersionParser} is supplied — parses the (possibly regex-extracted, possibly stripped)
 * text and reports the parsed version.
 *
 * <p>This transparently reimplements the extraction logic from the production
 * {@code HttpPrometheusCurrentSource} (backend, slices 01-03) — see
 * {@code backend/server/src/main/java/org/yardship/adapters/out/versionsource/current/httpprometheus/HttpPrometheusCurrentSource.java}
 * — rather than depending on it ({@code :backend:conf-check} must not depend on
 * {@code :backend:server}), so it can report the outcome instead of only throwing. Per ADR-0033,
 * "the first matching sample wins; conflicts are not refused": when more than one sample survives
 * the {@code --label} selector, the first in document order is still taken, but
 * {@link MetricResult#matchedSampleCount()} reports how many did, so the CLI's report — unlike the
 * backend's silent take-the-first behaviour — makes the ambiguity visible to the operator deciding
 * whether to deploy.
 *
 * <p>The optional {@code regex}'s selection rule is "first match that parses", exactly
 * {@code RegexVersionExtractor.firstIn}'s rule (backend) and {@link HeaderExtractionValidation}'s
 * own regex handling — never "largest": a current version is a single observation, not a
 * selection. Regex compilation/capture-group validation is shared with
 * {@link RegexExtractionValidation} and {@link HeaderExtractionValidation} via
 * {@code :backend:domain}'s {@link VersionPattern} rather than reimplemented a third/fourth time —
 * this half of the command CANNOT drift from the backend, unlike the hand-copied parser above.
 *
 * <ul>
 *   <li>{@code regex} fails to compile, or has no capture group 1 → {@link ValidationOutcome.ConfigInvalid}.</li>
 *   <li>The metric is absent from the body entirely → {@link ValidationOutcome.MetricValidButEmpty}
 *       with {@link MetricResult#metricAbsent()}.</li>
 *   <li>The metric is present, but {@code --label} matched no sample →
 *       {@link ValidationOutcome.MetricValidButEmpty} with {@link MetricResult#selectorMatchedNothing()},
 *       naming a bounded number of the label sets actually seen for that metric in the message —
 *       distinct wording from the "metric absent" case above, since the fix differs (a bad
 *       {@code --label} selector, not a bad {@code --metric} name).</li>
 *   <li>A sample matched, but {@code version-label} is absent from it →
 *       {@link ValidationOutcome.MetricValidButEmpty} with {@link MetricResult#versionLabelAbsent}.</li>
 *   <li>A sample matched and carried {@code version-label}, but its value is empty after trimming →
 *       {@link ValidationOutcome.MetricValidButEmpty} with {@link MetricResult#presentButEmpty}.</li>
 *   <li>No {@code regex}, no {@code parser} → {@link ValidationOutcome.MetricOk} with an
 *       extraction-only result (the trimmed label value).</li>
 *   <li>No {@code regex}, {@code parser} supplied, trimmed value parses →
 *       {@link ValidationOutcome.MetricOk} with a parsed result.</li>
 *   <li>No {@code regex}, {@code parser} supplied, trimmed value fails to parse →
 *       {@link ValidationOutcome.MetricValidButEmpty}, carrying the attempted value.</li>
 *   <li>{@code regex} supplied, no {@code parser} → {@link ValidationOutcome.MetricOk} with the
 *       FIRST match's capture group 1 as extraction-only text.</li>
 *   <li>{@code regex} supplied, {@code parser} supplied → the first match whose capture group 1
 *       parses wins (later matches are never preferred); if none parse,
 *       {@link ValidationOutcome.MetricValidButEmpty} carrying the whole trimmed label value.</li>
 * </ul>
 *
 * <p>{@code stripPreRelease} is applied to the successfully PARSED {@code VersionValue}, never to
 * the raw extracted text, matching {@link HeaderExtractionValidation} and the backend's own
 * {@code stripPrerelease ? version.withoutPreRelease() : version} ordering (ADR-0033:
 * "strip-prerelease applied last").
 */
public final class MetricExtractionValidation {

    /**
     * Upper bound on how many of the metric's seen label sets are named in the "selector matched
     * nothing" failure message, mirroring the backend's {@code MAX_LABEL_SETS_NAMED} — a
     * {@code /metrics} body can carry hundreds of samples of one metric, and the message must stay
     * operator-sized.
     */
    static final int MAX_LABEL_SETS_NAMED = 5;

    private final PrometheusExposition exposition = new PrometheusExposition();

    /**
     * @param body            the fetched/read Prometheus text-exposition body to search.
     * @param metric          the exact metric name to match.
     * @param labelSelector   exact-match label filters, ANDed; empty matches every sample of the
     *                        metric (mirrors an absent {@code labels:} config block).
     * @param versionLabel    the label name to read off the matched sample (defaults to
     *                        {@code "version"} one layer up, in {@code MetricCommand}).
     * @param regex           a Java regex with at least one capture group; group 1 is parsed per
     *                        candidate. Absent = the whole trimmed label value is used directly.
     * @param stripPreRelease when {@code true}, apply {@code VersionValue.withoutPreRelease()} to
     *                        the successfully parsed value. Has no effect when {@code parser} is
     *                        absent.
     * @param parser          the scheme-configured parser when {@code --scheme} was given; empty
     *                        when it was not (extraction-only run).
     */
    public ValidationOutcome validate(
            String body,
            String metric,
            Map<String, String> labelSelector,
            String versionLabel,
            Optional<String> regex,
            boolean stripPreRelease,
            Optional<VersionParser> parser) {

        VersionPattern pattern = null;
        if (regex.isPresent()) {
            try {
                pattern = new VersionPattern(regex.get());
            } catch (IllegalArgumentException e) {
                return new ValidationOutcome.ConfigInvalid(e.getMessage());
            }
        }

        List<PrometheusSample> samplesOfMetric = exposition.samplesOf(body, metric);
        List<PrometheusSample> selected = exposition.narrowBySelector(samplesOfMetric, labelSelector);

        if (selected.isEmpty()) {
            return samplesOfMetric.isEmpty()
                    ? metricAbsent(metric)
                    : selectorMatchedNothing(metric, samplesOfMetric);
        }

        PrometheusSample taken = selected.get(0);
        int matchedSampleCount = selected.size();
        Map<String, String> takenLabels = taken.labels();

        String rawValue = taken.labels().get(versionLabel);
        if (rawValue == null) {
            return versionLabelAbsent(versionLabel, matchedSampleCount, takenLabels);
        }
        String trimmed = rawValue.trim();
        if (trimmed.isEmpty()) {
            return versionLabelPresentButEmpty(versionLabel, matchedSampleCount, takenLabels);
        }

        return (pattern != null)
                ? validateWithRegex(matchedSampleCount, takenLabels, trimmed, pattern, stripPreRelease, parser)
                : validateWholeValue(matchedSampleCount, takenLabels, trimmed, stripPreRelease, parser);
    }

    private ValidationOutcome validateWholeValue(
            int matchedSampleCount, Map<String, String> takenLabels, String trimmed,
            boolean stripPreRelease, Optional<VersionParser> parser) {
        if (parser.isEmpty()) {
            return new ValidationOutcome.MetricOk(MetricResult.extractedOnly(matchedSampleCount, takenLabels, trimmed));
        }
        try {
            VersionValue value = parser.get().parse(trimmed);
            VersionValue reported = stripPreRelease ? value.withoutPreRelease() : value;
            return new ValidationOutcome.MetricOk(
                    MetricResult.parsed(matchedSampleCount, takenLabels, trimmed, stripPreRelease, reported));
        } catch (InvalidVersionException e) {
            return new ValidationOutcome.MetricValidButEmpty(
                    "Metric value '" + trimmed + "' did not parse under the configured scheme: " + e.getMessage(),
                    MetricResult.rejected(matchedSampleCount, takenLabels, trimmed, stripPreRelease, e.getMessage()));
        }
    }

    private ValidationOutcome validateWithRegex(
            int matchedSampleCount, Map<String, String> takenLabels, String trimmed, VersionPattern pattern,
            boolean stripPreRelease, Optional<VersionParser> parser) {
        List<String> candidates = pattern.rawCandidates(trimmed);

        if (parser.isEmpty()) {
            if (!candidates.isEmpty()) {
                return new ValidationOutcome.MetricOk(
                        MetricResult.extractedOnly(matchedSampleCount, takenLabels, candidates.get(0)));
            }
            return new ValidationOutcome.MetricValidButEmpty(
                    "No match for the configured regex in metric value '" + trimmed + "'.",
                    MetricResult.rejected(matchedSampleCount, takenLabels, trimmed, stripPreRelease, "no match"));
        }

        for (String candidateText : candidates) {
            try {
                VersionValue value = parser.get().parse(candidateText);
                VersionValue reported = stripPreRelease ? value.withoutPreRelease() : value;
                return new ValidationOutcome.MetricOk(
                        MetricResult.parsed(matchedSampleCount, takenLabels, candidateText, stripPreRelease, reported));
            } catch (InvalidVersionException ignored) {
                // Try the next match; the first PARSEABLE match wins, per ADR-0033 (never largest).
            }
        }
        return new ValidationOutcome.MetricValidButEmpty(
                "No match for the configured regex in metric value '" + trimmed
                        + "' parsed under the configured scheme.",
                MetricResult.rejected(matchedSampleCount, takenLabels, trimmed, stripPreRelease, "no parseable match"));
    }

    private ValidationOutcome metricAbsent(String metric) {
        return new ValidationOutcome.MetricValidButEmpty(
                "Metric '" + metric + "' was not present in the body.",
                MetricResult.metricAbsent());
    }

    /**
     * The "metric present, but {@code --label} matched no sample" failure (ADR-0033, issue 02) —
     * distinct wording from {@link #metricAbsent}, since the fix is different (a bad {@code
     * --label} selector, not a bad {@code --metric} name). Names up to
     * {@link #MAX_LABEL_SETS_NAMED} of the label sets actually seen for {@code metric}, in
     * document order, mirroring the backend's {@code selectorMatchedNothingMessage}.
     */
    private ValidationOutcome selectorMatchedNothing(String metric, List<PrometheusSample> samplesOfMetric) {
        String labelSetsSeen = samplesOfMetric.stream()
                .limit(MAX_LABEL_SETS_NAMED)
                .map(MetricExtractionValidation::renderLabelSet)
                .collect(Collectors.joining(", "));
        int sampleCount = samplesOfMetric.size();
        String seenClause = sampleCount + " sample" + (sampleCount == 1 ? "" : "s") + " of '" + metric + "' "
                + (sampleCount == 1 ? "was" : "were") + " seen, including: " + labelSetsSeen;
        return new ValidationOutcome.MetricValidButEmpty(
                "Metric '" + metric + "' was present in the body, but its '--label' selector matched no sample. "
                        + seenClause,
                MetricResult.selectorMatchedNothing());
    }

    private static String renderLabelSet(PrometheusSample sample) {
        return sample.labels().entrySet().stream()
                .map(entry -> entry.getKey() + "=\"" + entry.getValue() + "\"")
                .collect(Collectors.joining(",", "{", "}"));
    }

    private ValidationOutcome versionLabelAbsent(String versionLabel, int matchedSampleCount, Map<String, String> takenLabels) {
        return new ValidationOutcome.MetricValidButEmpty(
                "The matched sample did not carry the '" + versionLabel + "' label.",
                MetricResult.versionLabelAbsent(matchedSampleCount, takenLabels));
    }

    private ValidationOutcome versionLabelPresentButEmpty(String versionLabel, int matchedSampleCount, Map<String, String> takenLabels) {
        return new ValidationOutcome.MetricValidButEmpty(
                "The matched sample's '" + versionLabel + "' label was present but empty after trimming.",
                MetricResult.presentButEmpty(matchedSampleCount, takenLabels));
    }
}
