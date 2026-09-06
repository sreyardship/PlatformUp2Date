package org.yardship.adapters.out.versionsource.current.httpprometheus;

import org.yardship.core.domain.exceptions.InvalidVersionException;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionValue;
import org.yardship.core.ports.out.CurrentVersionSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The {@code http-prometheus} {@link CurrentVersionSource}: reads an app's current (deployed)
 * version from a named <b>label on a named metric</b> in a Prometheus text-exposition body. Per
 * {@code docs/adr/0033-http-prometheus-current-source.md} — the binding specification for this
 * kind — the version is always a label value, never the numeric sample value; the body is fetched
 * through {@link PrometheusBodyFetch} (which, unlike {@code http-header}'s fetch, gates on a 2xx
 * final response before this class ever sees a body); parsing is delegated to the pure
 * {@link PrometheusExposition}; and the <b>first</b> matching sample of {@code metric} in document
 * order is used, with no error raised over a disagreeing later sample. An optional {@code labels:}
 * selector (ADR-0033, issue 02) narrows which samples of {@code metric} are candidates before that
 * first-in-document-order rule applies — exact string equality on every configured entry, ANDed,
 * with no {@code !=} / {@code =~} / {@code !~}. It is an installation selector, not a conflict
 * remedy: it does not change the "first match wins, no error on disagreement" rule above, it only
 * changes what counts as a match.
 *
 * <p>A plain (non-CDI), per-app POJO holding a ready {@link PrometheusBodyFetch} — built and
 * injected by its factory — plus the metric name, the version-label name, and the app's
 * {@link VersionParser}. {@code Closeable} is unnecessary here: the underlying
 * {@code RedirectFollowingHttpGet} holds no resource needing release, matching
 * {@code HttpHeaderCurrentSource}.
 *
 * <p>No failure message this class raises ever embeds the fetched body itself, or any body-derived
 * value beyond the single failing {@code version-label} value — matching {@code
 * HttpHeaderCurrentSource}, which names the offending header value the same way. The "metric not
 * found" message names how many sample lines were seen (a count only, via
 * {@link PrometheusExposition#sampleLineCountIn}), never quoting them. The sole exception is the
 * "selector matched nothing" message (ADR-0033, issue 02): it names a bounded number of the label
 * sets actually seen for the configured {@code metric} — enough for the operator to correct
 * {@code labels:} — capped by {@link #MAX_LABEL_SETS_NAMED} and scoped to that metric only; every
 * other message in this class stays body-free.
 */
public class HttpPrometheusCurrentSource implements CurrentVersionSource {

    /**
     * Upper bound on how many of the metric's seen label sets are named in the "selector matched
     * nothing" failure message — a {@code /metrics} body can carry hundreds of samples of one
     * metric, and the message must stay operator-sized. The rule is simply: at most
     * {@link #MAX_LABEL_SETS_NAMED} label sets, in document order, scoped to samples of the named
     * metric only (see {@link #selectorMatchedNothingMessage}). When fewer samples than the cap
     * were seen — including exactly one, the single most common misconfiguration — every one of
     * them is named.
     */
    private static final int MAX_LABEL_SETS_NAMED = 5;

    private final PrometheusBodyFetch fetch;
    private final String url;
    private final String metric;
    private final String versionLabel;
    private final Map<String, String> labels;
    private final VersionParser parser;
    private final PrometheusExposition exposition = new PrometheusExposition();

    public HttpPrometheusCurrentSource(
            PrometheusBodyFetch fetch, String url, String metric, String versionLabel, VersionParser parser) {
        this(fetch, url, metric, versionLabel, Map.of(), parser);
    }

    /**
     * @param labels optional installation-selector map (ADR-0033, issue 02): exact-match label
     *               filters, ANDed, narrowing which samples of {@code metric} are candidates.
     *               Empty means every sample of the metric is a candidate, exactly as this class
     *               behaved before this field existed.
     */
    public HttpPrometheusCurrentSource(PrometheusBodyFetch fetch, String url, String metric, String versionLabel,
            Map<String, String> labels, VersionParser parser) {
        this.fetch = fetch;
        this.url = url;
        this.metric = metric;
        this.versionLabel = versionLabel;
        this.labels = labels;
        this.parser = parser;
    }

    @Override
    public VersionValue version() {
        String body = fetch.fetch();
        PrometheusSample sample = firstSample(body);
        String trimmedValue = trimmedLabelValue(sample);
        return parseVersion(trimmedValue);
    }

    private PrometheusSample firstSample(String body) {
        List<PrometheusSample> samplesOfMetric = exposition.samplesOf(body, metric);
        List<PrometheusSample> selected = exposition.narrowBySelector(samplesOfMetric, labels);
        if (!selected.isEmpty()) {
            return selected.get(0);
        }
        if (samplesOfMetric.isEmpty()) {
            long sampleLinesSeen = exposition.sampleLineCountIn(body);
            throw new IllegalStateException("The 'http-prometheus' current source's metric '" + metric
                    + "' was not present in the body fetched from '" + url + "' (" + sampleLinesSeen
                    + " sample line" + (sampleLinesSeen == 1 ? "" : "s") + " seen).");
        }
        throw new IllegalStateException(selectorMatchedNothingMessage(samplesOfMetric));
    }

    /**
     * The "metric present, but {@code labels:} matched no sample" failure (ADR-0033, issue 02) —
     * distinct from "metric absent" above, since the fix is different (a bad {@code labels:}
     * selector, not a bad {@code metric} name). Names up to {@link #MAX_LABEL_SETS_NAMED} of the
     * label sets actually seen for {@code metric}, so the operator can see what a corrected
     * selector must match. This is the one message in this class permitted to quote anything from
     * the fetched body — bounded, and scoped to samples of the named metric only.
     *
     * <p>At most {@link #MAX_LABEL_SETS_NAMED} label sets are named, in document order. When
     * fewer samples than the cap were seen — including exactly one, the single most common
     * misconfiguration (one endpoint, one typo'd selector) — every one of them is named; the
     * operator otherwise gets nothing to correct the selector against.
     */
    private String selectorMatchedNothingMessage(List<PrometheusSample> samplesOfMetric) {
        String labelSetsSeen = samplesOfMetric.stream()
                .limit(MAX_LABEL_SETS_NAMED)
                .map(HttpPrometheusCurrentSource::renderLabelSet)
                .collect(Collectors.joining(", "));
        int sampleCount = samplesOfMetric.size();
        String seenClause = sampleCount + " sample" + (sampleCount == 1 ? "" : "s") + " of '" + metric + "' "
                + (sampleCount == 1 ? "was" : "were") + " seen, including: " + labelSetsSeen;
        return "The 'http-prometheus' current source's metric '" + metric
                + "' was present in the body fetched from '" + url
                + "', but its 'labels:' selector matched no sample. " + seenClause;
    }

    private static String renderLabelSet(PrometheusSample sample) {
        return sample.labels().entrySet().stream()
                .map(entry -> entry.getKey() + "=\"" + entry.getValue() + "\"")
                .collect(Collectors.joining(",", "{", "}"));
    }

    private String trimmedLabelValue(PrometheusSample sample) {
        String rawValue = sample.labels().get(versionLabel);
        if (rawValue == null) {
            throw new IllegalStateException("The 'http-prometheus' current source's metric '" + metric
                    + "' matched, but its label '" + versionLabel + "' was absent (url '" + url + "').");
        }
        String trimmed = rawValue.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalStateException("The 'http-prometheus' current source's metric '" + metric
                    + "' matched, but its label '" + versionLabel
                    + "' was present but empty after trimming (url '" + url + "').");
        }
        return trimmed;
    }

    private VersionValue parseVersion(String trimmedValue) {
        try {
            return parser.parse(trimmedValue);
        } catch (InvalidVersionException ex) {
            throw new IllegalStateException("The 'http-prometheus' current source's metric '" + metric
                    + "' label '" + versionLabel + "' did not yield a parseable version: " + ex.getMessage()
                    + " (url '" + url + "').");
        }
    }
}
