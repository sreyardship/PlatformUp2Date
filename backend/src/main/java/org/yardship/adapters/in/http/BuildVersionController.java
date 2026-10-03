package org.yardship.adapters.in.http;

import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/** Instance-local artifact identity; deliberately independent of monitoring state. */
@Path("/api/version")
@PermitAll
public class BuildVersionController {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(responseCode = "200", description = "Build version of the responding backend instance",
            content = @Content(schema = @Schema(implementation = BuildVersion.class)))
    public Response getVersion() {
        return Response.ok(new BuildVersion(EmbeddedBuildVersion.VERSION))
                .header("Cache-Control", "no-store")
                .build();
    }
}
