package org.yardship.adapters.out.versionsource.latest.githubrelease;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Encoded;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

/** REST client for one raw GitHub releases response, before redirect or status handling. */
@Path("")
interface GithubReleaseResponseClient {

    @GET
    @Path("/{path: .*}")
    Response releaseArray(@Encoded @PathParam("path") String path);

}
