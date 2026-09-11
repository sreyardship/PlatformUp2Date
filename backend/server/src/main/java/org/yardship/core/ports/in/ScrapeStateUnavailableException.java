package org.yardship.core.ports.in;

/**
 * Raised by the use case when it cannot answer, because the Scrape state is unavailable.
 *
 * <p>This is the server-side name for what a Surface calls <em>Backend unavailable</em>: the
 * request got no answer it could use, so the Surface must show the unavailability itself rather
 * than an empty fleet. A JAX-RS mapper turns it into HTTP 503, keeping the read path fail-closed —
 * {@code GET /api/v1/version} never degrades to a 200 with stale or empty data.
 *
 * <p><b>Why this and {@code ScrapeStateAccessException} both exist.</b> They mean nearly the same
 * event, and that is not an oversight. {@code core.ports.out.ScrapeStateAccessException} is what
 * the store states — it could not read or write, and the substrate detail lives in its message and
 * cause. This one is what the use case states — it has no answer to give. The service translates at
 * the boundary, preserving the cause. What the pair buys is one property: {@code adapters.in} never
 * imports {@code core.ports.out}, so the driving side of the hexagon never names a driven type.
 * Collapsing them back into one type gives that property up.
 */
public class ScrapeStateUnavailableException extends RuntimeException {

    public ScrapeStateUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
