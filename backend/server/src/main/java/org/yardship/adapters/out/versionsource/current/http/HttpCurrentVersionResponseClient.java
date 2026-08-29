package org.yardship.adapters.out.versionsource.current.http;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Encoded;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

/** REST client for one raw current-version response, before redirect or status handling. */
@Path("")
interface HttpCurrentVersionResponseClient {

    @GET
    @Path("/{path: .*}")
    Response getCurrentVersion(@Encoded @PathParam("path") String path);
}
