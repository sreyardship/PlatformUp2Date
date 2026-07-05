package org.yardship.adapters.in.http;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yardship.adapters.out.versionsource.ChangelogTemplates;
import org.yardship.core.ports.in.ApplicationVersionPort;

import java.util.HashMap;
import java.util.Map;

@Path("/api/v1")
public class VersionController {

    Logger logger = LoggerFactory.getLogger(VersionController.class);

    private final ApplicationVersionPort applicationVersionPort;
    private final ChangelogTemplates changelogTemplates;

    public VersionController(ApplicationVersionPort applicationVersionPort, ChangelogTemplates changelogTemplates) {
        this.applicationVersionPort = applicationVersionPort;
        this.changelogTemplates = changelogTemplates;
    }

    @GET
    @Path("version")
    public Map<String, ApplicationStatus> getVersion() {
        Map<String, ApplicationStatus> appStatusList = new HashMap<>();

        // Fail closed: when the shared snapshot is unavailable (Valkey unreachable) the
        // port throws ScrapeStateUnavailableException, mapped to 503 by
        // ScrapeStateUnavailableExceptionMapper — never a 200 with stale or empty data.
        applicationVersionPort.getApplications()
                .forEach(app -> {
                            var status = ApplicationStatus.from(app, changelogTemplates.forApp(app.name()));
                            appStatusList.put(app.name(), status);
                        }
                );
        return appStatusList;
    }
}
