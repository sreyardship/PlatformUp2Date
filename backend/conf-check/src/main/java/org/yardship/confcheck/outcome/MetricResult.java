package org.yardship.confcheck.outcome;

import org.yardship.core.domain.primitives.VersionValue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code metric} subcommand's report line: how many samples of the configured metric survived
 * the {@code --label} selector ({@link #matchedSampleCount()}), the label set of the sample
 * actually taken (the first, per document order — {@link #takenLabels()}), the raw (trimmed)
 * {@code version-label} value when the matched sample carried one, and its optional parse result
 * when a scheme was given.
 *
 * <p>Carrying {@link #matchedSampleCount()} unconditionally — even on a passing report — is the
 * point of this type: per ADR-0033 ("The first matching sample wins; conflicts are not refused"),
 * several samples can legitimately match one metric/selector combination (a rollout in flight, or
 * a loosely-scoped selector), and this command's report must make that ambiguity visible — naming
 * how many samples matched and which one's labels were actually used — rather than silently
 * reporting only the winner as if it were the sole candidate.
 *
 * <p>Mirrors {@link HeaderResult}'s optional-scheme shape (at most one of {@link #parsed()} /
 * {@link #rejectionReason()} present; both absent means "no scheme was requested, extraction-only"),
 * plus the {@code matchedSampleCount}/{@code takenLabels} fields {@code header} has no equivalent
 * for (a response has no notion of "how many samples matched").
 */
public record MetricResult(
        int matchedSampleCount,
        Map<String, String> takenLabels,
        Optional<String> rawText,
        boolean strippedPreRelease,
        Optional<VersionValue> parsed,
        Optional<String> rejectionReason) {

    public MetricResult {
        takenLabels = Collections.unmodifiableMap(new LinkedHashMap<>(takenLabels));
        if (parsed.isPresent() && rejectionReason.isPresent()) {
            throw new IllegalArgumentException(
                    "MetricResult must not have both parsed and rejectionReason present");
        }
        if (rawText.isEmpty() && (parsed.isPresent() || rejectionReason.isPresent())) {
            throw new IllegalArgumentException(
                    "MetricResult must not carry parsed/rejectionReason without rawText");
        }
    }

    /**
     * The configured metric was not present in the body at all — no sample line matched its name,
     * so there is nothing to narrow by {@code --label} and no label set to report.
     */
    public static MetricResult metricAbsent() {
        return new MetricResult(0, Map.of(), Optional.empty(), false, Optional.empty(), Optional.empty());
    }

    /**
     * The metric was present, but the {@code --label} selector matched none of its samples. Same
     * shape as {@link #metricAbsent()} — the two are told apart by the {@link ValidationOutcome}
     * message text, which is where the bounded, capped set of label sets actually seen is named.
     */
    public static MetricResult selectorMatchedNothing() {
        return new MetricResult(0, Map.of(), Optional.empty(), false, Optional.empty(), Optional.empty());
    }

    /** A sample matched, but the configured {@code version-label} was absent from it. */
    public static MetricResult versionLabelAbsent(int matchedSampleCount, Map<String, String> takenLabels) {
        return new MetricResult(matchedSampleCount, takenLabels, Optional.empty(), false, Optional.empty(), Optional.empty());
    }

    /** A sample matched and carried the {@code version-label}, but its value was empty after trimming. */
    public static MetricResult presentButEmpty(int matchedSampleCount, Map<String, String> takenLabels) {
        return new MetricResult(matchedSampleCount, takenLabels, Optional.of(""), false, Optional.empty(), Optional.empty());
    }

    /** No scheme was given (extraction-only): the label resolved to {@code rawText}, nothing more attempted. */
    public static MetricResult extractedOnly(int matchedSampleCount, Map<String, String> takenLabels, String rawText) {
        return new MetricResult(matchedSampleCount, takenLabels, Optional.of(rawText), false, Optional.empty(), Optional.empty());
    }

    /** A scheme was given and {@code rawText} parsed successfully into {@code value}. */
    public static MetricResult parsed(
            int matchedSampleCount, Map<String, String> takenLabels, String rawText, boolean strippedPreRelease, VersionValue value) {
        return new MetricResult(
                matchedSampleCount, takenLabels, Optional.of(rawText), strippedPreRelease, Optional.of(value), Optional.empty());
    }

    /** A scheme was given but {@code rawText} failed to parse/match under it. */
    public static MetricResult rejected(
            int matchedSampleCount, Map<String, String> takenLabels, String rawText, boolean strippedPreRelease, String reason) {
        return new MetricResult(
                matchedSampleCount, takenLabels, Optional.of(rawText), strippedPreRelease, Optional.empty(), Optional.of(reason));
    }

    /** {@code true} if a scheme (and/or regex requiring a scheme to select among matches) was requested. */
    public boolean schemeRequested() {
        return parsed.isPresent() || rejectionReason.isPresent();
    }
}
