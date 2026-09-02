package org.yardship.adapters.out.versionsource.current.httpjson;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Path("")
public interface HttpJsonCurrentVersionClient {

    @GET
    JsonNode getCurrentVersion();
}
