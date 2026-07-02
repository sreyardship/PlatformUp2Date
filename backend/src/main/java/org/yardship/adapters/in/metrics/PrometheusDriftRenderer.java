package org.yardship.adapters.in.metrics;

import jakarta.enterprise.context.ApplicationScoped;
import org.yardship.core.domain.primitives.VersionApplication;
import org.yardship.core.domain.primitives.VersionValue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class PrometheusDriftRenderer {

    private static final String DRIFT_METRIC   = "pu2d_version_drift_level";
    private static final String SUCCESS_METRIC = "pu2d_scrape_last_success_timestamp_seconds";
    private static final String FAILURE_METRIC = "pu2d_scrape_last_failure_timestamp_seconds";

    public String render(List<VersionApplication> applications) {
        StringBuilder builder = new StringBuilder();
        appendDriftFamily(builder, applications);
        appendSuccessFamily(builder, applications);
        appendFailureFamily(builder, applications);
        return builder.toString();
    }

    // -------------------------------------------------------------------------
    // Drift family (resolved apps only)
    // -------------------------------------------------------------------------

    private static void appendDriftFamily(StringBuilder builder, List<VersionApplication> applications) {
        appendFamilyHeader(builder, DRIFT_METRIC,
                "How far the deployed version is behind latest (0=current, 1=patch, 2=minor, 3=major)");
        for (VersionApplication app : applications) {
            if (!app.isResolved()) {
                continue;
            }
            builder.append(DRIFT_METRIC)
                    .append("{app=\"").append(escapeLabelValue(app.name())).append("\"} ")
                    .append(gaugeValue(app.drift()))
                    .append("\n");
        }
    }

    // -------------------------------------------------------------------------
    // Per-side success timestamp family
    // -------------------------------------------------------------------------

    private static void appendSuccessFamily(StringBuilder builder, List<VersionApplication> applications) {
        appendFamilyHeader(builder, SUCCESS_METRIC,
                "Unix timestamp of the last successful scrape for this app side");
        for (VersionApplication app : applications) {
            appendSideTimestampIfPresent(builder, SUCCESS_METRIC, app.name(),
                    "current", app.current().lastSuccessAt().map(Instant::getEpochSecond));
            appendSideTimestampIfPresent(builder, SUCCESS_METRIC, app.name(),
                    "latest", app.latest().lastSuccessAt().map(Instant::getEpochSecond));
        }
    }

    // -------------------------------------------------------------------------
    // Per-side failure timestamp family
    // -------------------------------------------------------------------------

    private static void appendFailureFamily(StringBuilder builder, List<VersionApplication> applications) {
        appendFamilyHeader(builder, FAILURE_METRIC,
                "Unix timestamp of the last failed scrape for this app side");
        for (VersionApplication app : applications) {
            appendSideTimestampIfPresent(builder, FAILURE_METRIC, app.name(),
                    "current", app.current().lastFailureAt().map(Instant::getEpochSecond));
            appendSideTimestampIfPresent(builder, FAILURE_METRIC, app.name(),
                    "latest", app.latest().lastFailureAt().map(Instant::getEpochSecond));
        }
    }

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    private static void appendFamilyHeader(StringBuilder builder, String metricName, String description) {
        builder.append("# HELP ").append(metricName).append(" ").append(description).append("\n");
        builder.append("# TYPE ").append(metricName).append(" gauge\n");
    }

    private static void appendSideTimestampIfPresent(StringBuilder builder, String metricName,
            String appName, String side, Optional<Long> epochSeconds) {
        epochSeconds.ifPresent(seconds ->
                builder.append(metricName)
                        .append("{app=\"").append(escapeLabelValue(appName))
                        .append("\",side=\"").append(side).append("\"} ")
                        .append(seconds)
                        .append("\n"));
    }

    private static int gaugeValue(VersionValue.Diff drift) {
        return switch (drift) {
            case MAJOR -> 3;
            case MINOR -> 2;
            case PATCH -> 1;
            case NONE -> 0;
        };
    }

    private static String escapeLabelValue(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }
}
