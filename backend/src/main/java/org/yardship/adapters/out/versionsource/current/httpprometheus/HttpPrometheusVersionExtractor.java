package org.yardship.adapters.out.versionsource.current.httpprometheus;

import org.yardship.adapters.out.versionsource.VersionFetchException;
import org.yardship.adapters.out.versionsource.http.HttpGetResponse;
import org.yardship.adapters.out.versionsource.http.HttpVersionExtractor;
import org.yardship.adapters.out.versionsource.regex.RegexVersionExtractor;
import org.yardship.core.domain.exceptions.InvalidVersionException;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionValue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The {@code http-prometheus} rule: read an app's current version from a named <b>label on a
 * named metric</b> in a Prometheus text-exposition body. Per
 * {@code docs/adr/0033-http-prometheus-current-source.md} — the binding specification for this
 * kind — the final response must be a 2xx (the body IS the resource, so a login page or a proxy
 * error page must fail the read rather than be parsed — unlike {@code http-header}); the version
 * is always a label value, never the numeric sample value; parsing is delegated to the pure
 * {@link PrometheusExposition}; and the <b>first</b> matching sample of {@code metric} in document
 * order is used, with no error raised over a disagreeing later sample. An optional {@code labels:}
 * selector (ADR-0033, issue 02) narrows which samples of {@code metric} are candidates before that
 * first-in-document-order rule applies — exact string equality on every configured entry, ANDed,
 * with no {@code !=} / {@code =~} / {@code !~}. It is an installation selector, not a conflict
 * remedy: it does not change the "first match wins, no error on disagreement" rule above, it only
 * changes what counts as a match.
 *
 * <p>No failure message this class raises ever embeds the fetched body itself, or any body-derived
 * value beyond the single failing {@code version-label} value — matching
 * {@code HttpHeaderVersionExtractor}, which names the offending header value the same way. The
 * non-2xx message names the status and the URL only, following {@code http-regex}'s own gate. The
 * "metric not found" message names how many sample lines were seen (a count only, via
 * {@link PrometheusExposition#sampleLineCountIn}), never quoting them. The sole exception is the
 * "selector matched nothing" message (ADR-0033, issue 02): it names a bounded number of the label
 * sets actually seen for the configured {@code metric} — enough for the operator to correct
 * {@code labels:} — capped by {@link #MAX_LABEL_SETS_NAMED} and scoped to that metric only; every
 * other message in this class stays body-free.
 */
public final class HttpPrometheusVersionExtractor implements HttpVersionExtractor {

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

    private final String url;
    private final String metric;
    private final String versionLabel;
    private final Map<String, String> labels;
    private final Optional<RegexVersionExtractor> regex;
    private final boolean stripPrerelease;
    private final VersionParser parser;
    private final PrometheusExposition exposition = new PrometheusExposition();

    /**
     * @param labels optional installation-selector map (ADR-0033, issue 02): exact-match label
     *               filters, ANDed, narrowing which samples of {@code metric} are candidates.
     *               Empty means every sample of the metric is a candidate.
     * @param regex optional {@code regex} extraction (ADR-0033, issue 03): applied to the trimmed
     *              label value via {@link RegexVersionExtractor#firstIn} — capture group 1 of the
     *              FIRST match, never the largest (a current version is a single observation, not
     *              a selection). Absent means the trimmed label value is parsed directly.
     * @param stripPrerelease when {@code true}, clears the prerelease segment of the resolved
     *                        version before it is reported ({@link VersionValue#withoutPreRelease()}),
     *                        exactly as for {@code http-json}, {@code http-header}, {@code
     *                        k8s-image} and {@code oci-registry}.
     */
    public HttpPrometheusVersionExtractor(String url, String metric, String versionLabel,
            Map<String, String> labels, Optional<RegexVersionExtractor> regex, boolean stripPrerelease,
            VersionParser parser) {
        this.url = url;
        this.metric = metric;
        this.versionLabel = versionLabel;
        this.labels = labels;
        this.regex = regex;
        this.stripPrerelease = stripPrerelease;
        this.parser = parser;
    }

    @Override
    public VersionValue extract(HttpGetResponse response) {
        if (!response.isSuccess()) {
            throw new VersionFetchException("The 'http-prometheus' current source got a non-success "
                    + "HTTP status " + response.status() + " fetching '" + url + "'.",
                    response.status(), "");
        }
        PrometheusSample sample = firstSample(response.body());
        String trimmedValue = trimmedLabelValue(sample);
        VersionValue version = parseVersion(trimmedValue);
        return stripPrerelease ? version.withoutPreRelease() : version;
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
     */
    private String selectorMatchedNothingMessage(List<PrometheusSample> samplesOfMetric) {
        String labelSetsSeen = samplesOfMetric.stream()
                .limit(MAX_LABEL_SETS_NAMED)
                .map(HttpPrometheusVersionExtractor::renderLabelSet)
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
        if (regex.isPresent()) {
            return regex.get().firstIn(trimmedValue)
                    .orElseThrow(() -> unparseableVersion(trimmedValue,
                            "the configured 'regex' matched nothing parseable"));
        }
        try {
            return parser.parse(trimmedValue);
        } catch (InvalidVersionException ex) {
            throw unparseableVersion(trimmedValue, ex.getMessage());
        }
    }

    private IllegalStateException unparseableVersion(String trimmedValue, String reason) {
        return new IllegalStateException("The 'http-prometheus' current source's metric '" + metric
                + "' label '" + versionLabel + "' had value '" + trimmedValue
                + "', which did not yield a parseable version: " + reason + " (url '" + url + "').");
    }
}
