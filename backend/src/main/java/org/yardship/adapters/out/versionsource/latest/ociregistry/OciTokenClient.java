package org.yardship.adapters.out.versionsource.latest.ociregistry;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

/**
 * Quarkus REST client for minting a bearer token from the registry's advertised {@code realm}
 * endpoint (leg 2 of the OCI bearer-token dance, ADR-0013).
 *
 * <p>Built against the full {@code realm} URL as base URI (e.g.
 * {@code https://auth.docker.io/token}), so no additional {@code @Path} is needed on the method.
 * Query parameters are the verbatim {@code service} and {@code scope} values echoed from the
 * challenge. An optional {@link org.yardship.adapters.out.versionsource.auth.BasicAuthFilter}
 * is registered when credentials are present.
 */
@Path("")
public interface OciTokenClient {

    @GET
    Response mint(@QueryParam("service") String service, @QueryParam("scope") String scope);
}
