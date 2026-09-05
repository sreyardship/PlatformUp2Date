package org.yardship.adapters.out.versionsource.current.httpprometheus;

import org.yardship.core.domain.exceptions.InvalidVersionException;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionValue;
import org.yardship.core.ports.out.CurrentVersionSource;

import java.util.List;

/**
 * The {@code http-prometheus} {@link CurrentVersionSource}: reads an app's current (deployed)
 * version from a named <b>label on a named metric</b> in a Prometheus text-exposition body. Per
 * {@code docs/adr/0033-http-prometheus-current-source.md} — the binding specification for this
 * kind — the version is always a label value, never the numeric sample value; the body is fetched
 * through {@link PrometheusBodyFetch} (which, unlike {@code http-header}'s fetch, gates on a 2xx
 * final response before this class ever sees a body); parsing is delegated to the pure
 * {@link PrometheusExposition}; and the <b>first</b> matching sample of {@code metric} in document
 * order is used, with no error raised over a disagreeing later sample.
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
 * {@link PrometheusExposition#sampleLineCountIn}), never quoting them.
 */
public class HttpPrometheusCurrentSource implements CurrentVersionSource {

    private final PrometheusBodyFetch fetch;
    private final String url;
    private final String metric;
    private final String versionLabel;
    private final VersionParser parser;
    private final PrometheusExposition exposition = new PrometheusExposition();

    public HttpPrometheusCurrentSource(
            PrometheusBodyFetch fetch, String url, String metric, String versionLabel, VersionParser parser) {
        this.fetch = fetch;
        this.url = url;
        this.metric = metric;
        this.versionLabel = versionLabel;
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
        List<PrometheusSample> samples = exposition.samplesOf(body, metric);
        if (samples.isEmpty()) {
            long sampleLinesSeen = exposition.sampleLineCountIn(body);
            throw new IllegalStateException("The 'http-prometheus' current source's metric '" + metric
                    + "' was not present in the body fetched from '" + url + "' (" + sampleLinesSeen
                    + " sample line" + (sampleLinesSeen == 1 ? "" : "s") + " seen).");
        }
        return samples.get(0);
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
