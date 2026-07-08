package org.yardship.adapters.out.scrapestate;

/**
 * Thrown when the Valkey-backed scrape state cannot be read or written because the
 * backing store is unreachable. The service lets this propagate (fail closed) and a
 * JAX-RS mapper turns it into HTTP 503, so {@code GET /api/v1/version} never degrades
 * to a 200 with stale/empty data when Valkey is down.
 */
public class ScrapeStateUnavailableException extends RuntimeException {

    public ScrapeStateUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
