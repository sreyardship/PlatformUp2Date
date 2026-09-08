package org.yardship.adapters.out.versionsource.current.httpprometheus;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One line of a Prometheus text-exposition body matching a configured metric name, reduced to
 * exactly what the {@code http-prometheus} kind needs: its label map. Adapter-local, not a domain
 * concept — it models a wire format, not a business fact (see
 * {@code docs/adr/0033-http-prometheus-current-source.md}, "A hand-rolled parser, scoped to what
 * the kind needs").
 *
 * <p>Deliberately carries no sample value, timestamp, metric type, or help text: the version this
 * kind reads is always a LABEL, never the numeric sample value, and {@link PrometheusExposition}
 * never parses those other fields because no caller needs them.
 *
 * <p>A sample line with no label set at all (e.g. {@code some_metric 1}) yields an empty
 * {@link #labels()} map, not a missing {@link PrometheusSample} — the metric still matched, it
 * simply has no labels to look a version up in.
 *
 * <p>{@link #labels()} is defensively copied in the compact constructor so this record is an
 * actual value type: without it, the caller's own (mutable) map would be exposed through the
 * accessor and could be mutated out from under every holder of this sample. The copy is
 * order-preserving — it must stay that way, since document order is meaningful wherever this
 * kind's first-wins rules apply.
 */
public record PrometheusSample(Map<String, String> labels) {

    public PrometheusSample {
        labels = Collections.unmodifiableMap(new LinkedHashMap<>(labels));
    }
}
