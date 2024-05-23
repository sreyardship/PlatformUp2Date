package org.yardship.adapters.out.versionclient;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(baseUri = "https://deployments.sreyardship.com/")
public interface YardshipClient {

    @GET
    @Path("/api/version")
    VersionResponseDTO getApiVersion();
}
