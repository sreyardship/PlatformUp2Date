package org.yardship.adapters.in;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.yardship.core.ports.in.VersionPort;

@Path("/api/v1")
public class VersionController {

    private final VersionPort versionPort;

    public VersionController(VersionPort versionPort) {
        this.versionPort = versionPort;
    }

    @GET
    @Path("hello")
    public String hello() {
        return "Hello, world!";
    }

    @GET
    @Path("version")
    public String getVersion() {
        return versionPort.getCurrentVersion().value();
    }
}
