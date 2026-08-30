package org.yardship.adapters.out.versionsource.latest.ociregistry;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Encoded;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

/**
 * Quarkus REST client for minting a bearer token from the registry's advertised {@code realm}
 * endpoint (leg 2 of the OCI bearer-token dance, ADR-0013).
 *
 * <p>The normal method can be built against the full {@code realm} URL (e.g.
 * {@code https://auth.docker.io/token}). Redirect-aware callers use {@link #mintAt(String)} with
 * an origin-only base URI, allowing each hop to decide whether configured Basic credentials may be
 * retained. Query parameters are the verbatim {@code service} and {@code scope} values echoed from
 * the challenge. An optional
 * {@link org.yardship.adapters.out.versionsource.auth.BasicAuthFilter} is registered when
 * credentials are present.
 */
@Path("")
public interface OciTokenClient {

    @GET
    Response mint(@QueryParam("service") String service, @QueryParam("scope") String scope);

    /**
     * Invokes the token endpoint using an already-resolved request path. This is used by the
     * explicit redirect walker so each hop can decide whether the Basic credential is retained.
     */
    @GET
    @Path("/{path: .*}")
    Response mintAt(@Encoded @PathParam("path") String path);
}
