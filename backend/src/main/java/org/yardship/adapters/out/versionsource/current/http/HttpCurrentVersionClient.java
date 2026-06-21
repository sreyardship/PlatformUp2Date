package org.yardship.adapters.out.versionsource.current.http;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Path("")
public interface HttpCurrentVersionClient {

    @GET
    JsonNode getCurrentVersion();
}
