package org.yardship.adapters.out.versionsource.latest.ociregistry;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

/**
 * Quarkus REST client for the OCI Distribution Spec {@code GET /v2/<repo>/tags/list} endpoint.
 * Returns {@link Response} so the caller can inspect the HTTP status and headers — the
 * {@code WWW-Authenticate} header on a {@code 401} (for the bearer-token dance, ADR-0013) and the
 * {@code Link} header for pagination across pages (ADR-0014).
 *
 * <p>This single interface serves every fetch path; {@code OciRegistryLatestSource} decides which
 * providers to register on the {@link io.quarkus.rest.client.reactive.QuarkusRestClientBuilder}:
 * an unauthenticated build (no exception mapper) for the initial probe and anonymous pages, and an
 * authenticated build (bearer-token filter + {@code VersionResponseExceptionMapper}) for the
 * post-dance retry. The differences are per-builder configuration, not per-interface contract.
 *
 * <p>The base URI supplied to the builder already includes the registry host AND the full
 * {@code /v2/{repo}} path, so this interface only needs the trailing {@code /tags/list} segment.
 *
 * <p>{@code n} is the OCI page-size query parameter; {@code last} is the pagination cursor
 * extracted from the {@code Link: rel="next"} header of the previous response (null on the
 * first request). Both are omitted from the wire request when null.
 */
@Path("")
public interface OciRegistryTagsClient {

    @GET
    @Path("/tags/list")
    Response tagsList(@QueryParam("n") Integer n, @QueryParam("last") String last);
}
