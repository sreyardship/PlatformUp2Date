package org.yardship.adapters.in;

import jakarta.enterprise.context.ApplicationScoped;
import org.yardship.core.domain.primitives.Version;
import org.yardship.core.domain.primitives.VersionApplication;

import java.util.List;

@ApplicationScoped
public class PrometheusDriftRenderer {

    private static final String HELP_LINE =
            "# HELP pu2d_version_drift_level How far the deployed version is behind latest "
                    + "(0=current, 1=patch, 2=minor, 3=major)\n";
    private static final String TYPE_LINE = "# TYPE pu2d_version_drift_level gauge\n";

    public String render(List<VersionApplication> applications) {
        StringBuilder builder = new StringBuilder();
        builder.append(HELP_LINE);
        builder.append(TYPE_LINE);
        for (VersionApplication application : applications) {
            builder.append("pu2d_version_drift_level{app=\"")
                    .append(escapeLabelValue(application.name()))
                    .append("\"} ")
                    .append(gaugeValue(application.drift()))
                    .append("\n");
        }
        return builder.toString();
    }

    private static int gaugeValue(Version.Diff drift) {
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
