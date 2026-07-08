package org.yardship.adapters.in.http;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.yardship.adapters.out.scrapestate.ScrapeStateUnavailableException;

/**
 * Maps a {@link ScrapeStateUnavailableException} (Valkey unreachable, surfaced by the
 * scrape-state store and propagated by the service) to HTTP 503. This keeps the read
 * path fail-closed: {@code GET /api/v1/version} returns 503 rather than degrading to a
 * 200 with stale or empty data. Scoped to this exception only, so genuine bugs still
 * surface as 500s.
 */
@Provider
public class ScrapeStateUnavailableExceptionMapper implements ExceptionMapper<ScrapeStateUnavailableException> {

    @Override
    public Response toResponse(ScrapeStateUnavailableException exception) {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(exception.getMessage())
                .build();
    }
}
