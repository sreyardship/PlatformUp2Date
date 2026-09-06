package org.yardship.confcheck.metric;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One line of a Prometheus text-exposition body matching a configured metric name, reduced to
 * exactly what the {@code metric} subcommand needs: its label map.
 *
 * <p>This is the {@code conf-check}-local twin of the backend's
 * {@code org.yardship.adapters.out.versionsource.current.httpprometheus.PrometheusSample}
 * (issue 05's "hand copy" — see {@link PrometheusExposition}'s javadoc for why {@code
 * :backend:conf-check} cannot depend on {@code :backend:server} and reimplements instead of
 * sharing). Keep the two in lockstep by hand when either changes.
 *
 * <p>Deliberately carries no sample value, timestamp, metric type, or help text: the version this
 * command reads is always a LABEL, never the numeric sample value.
 *
 * <p>A sample line with no label set at all (e.g. {@code some_metric 1}) yields an empty
 * {@link #labels()} map, not a missing {@link PrometheusSample} — the metric still matched, it
 * simply has no labels to look a version up in.
 *
 * <p>{@link #labels()} is defensively copied in the compact constructor, order-preserving, since
 * document order is meaningful wherever this command's first-wins rules apply.
 */
public record PrometheusSample(Map<String, String> labels) {

    public PrometheusSample {
        labels = Collections.unmodifiableMap(new LinkedHashMap<>(labels));
    }
}
