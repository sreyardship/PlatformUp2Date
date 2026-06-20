package org.yardship.adapters.out.versionclient;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Path("")
public interface CurrentVersionClient {

    @GET
    JsonNode getCurrentVersion();
}
