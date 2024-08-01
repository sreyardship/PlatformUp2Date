package org.yardship.adapters.in;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yardship.adapters.out.versionclient.ApplicationConfigLoader;
import org.yardship.core.ports.in.ApplicationVersionPort;

import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;

@Path("/api/v1")
public class VersionController {

    Logger logger = LoggerFactory.getLogger(VersionController.class);

    private final ApplicationVersionPort applicationVersionPort;

    public VersionController(ApplicationVersionPort applicationVersionPort) {
        this.applicationVersionPort = applicationVersionPort;
    }

    @Inject
    ApplicationConfigLoader loader;

    @GET
    @Path("version")
    public Map<String, ApplicationStatus> getVersion() throws URISyntaxException {
        Map<String, ApplicationStatus> appStatusList = new HashMap<>();

        applicationVersionPort.getApplications()
                .forEach(app -> {
                            var status = new ApplicationStatus(app.current().value(), app.latest().value());
                            appStatusList.put(app.name(), status);
                        }
                );
        return appStatusList;
    }
}
