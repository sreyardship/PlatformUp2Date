package org.yardship.adapters.in.http;

import org.yardship.core.domain.primitives.VersionApplication;

public record ApplicationStatus(String current, String latest, boolean outdated, String drift) {

    public static ApplicationStatus from(VersionApplication app) {
        return new ApplicationStatus(
                app.current().value(),
                app.latest().value(),
                app.isOld(),
                app.drift().name());
    }
}
