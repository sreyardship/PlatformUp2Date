package org.yardship.adapters.in.metrics;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import org.yardship.core.ports.in.ApplicationVersionPort;
import org.yardship.core.ports.in.ConfigErrorPort;

@Path("/metrics")
public class MetricsController {

    private final ApplicationVersionPort applicationVersionPort;
    private final PrometheusDriftRenderer renderer;
    private final ConfigErrorPort configErrorPort;

    public MetricsController(ApplicationVersionPort applicationVersionPort,
                            PrometheusDriftRenderer renderer,
                            ConfigErrorPort configErrorPort) {
        this.applicationVersionPort = applicationVersionPort;
        this.renderer = renderer;
        this.configErrorPort = configErrorPort;
    }

    @GET
    @Produces("text/plain; version=0.0.4; charset=utf-8")
    public String getMetrics() {
        return renderer.render(applicationVersionPort.getApplications(), configErrorPort.allConfigErrors(),
                configErrorPort.unnamedAppCount());
    }
}
