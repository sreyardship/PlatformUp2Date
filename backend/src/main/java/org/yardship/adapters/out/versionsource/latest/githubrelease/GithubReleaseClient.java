package org.yardship.adapters.out.versionsource.latest.githubrelease;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;

import java.util.List;

@Path("")
public interface GithubReleaseClient {

    @GET
    @Path("/releases")
    List<GithubReleaseResponseDTO> releases(@QueryParam("per_page") int perPage);
}
