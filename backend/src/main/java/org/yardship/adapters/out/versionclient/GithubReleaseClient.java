package org.yardship.adapters.out.versionclient;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Path("")
public interface GithubReleaseClient {

    @GET
    GithubReleaseResponseDTO getLatestRelease();
}
