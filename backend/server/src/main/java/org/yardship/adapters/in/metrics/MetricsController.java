package org.yardship.adapters.in.metrics;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import org.yardship.core.ports.in.ApplicationVersionPort;

@Path("/metrics")
public class MetricsController {

    private final ApplicationVersionPort applicationVersionPort;
    private final PrometheusDriftRenderer renderer;

    public MetricsController(ApplicationVersionPort applicationVersionPort,
                            PrometheusDriftRenderer renderer) {
        this.applicationVersionPort = applicationVersionPort;
        this.renderer = renderer;
    }

    @GET
    @Produces("text/plain; version=0.0.4; charset=utf-8")
    public String getMetrics() {
        return renderer.render(applicationVersionPort.getApplications());
    }
}
