package org.yardship.confcheck.port;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Obtains a whole HTTP response — status code and headers — for a fetch-backed validation that
 * needs more than a body, decoupling "where the response comes from" (a live HTTP fetch, an
 * offline fixture) from the validators that consume it. A sibling of {@link BodySource}, not a
 * replacement or a widening of it: {@link BodySource} means "the body" and keeps meaning that.
 *
 * <p>The defining difference from {@link BodySource}: a {@link BodySource} adapter that fetches
 * live (e.g. {@code LiveHttpBodySource}) throws {@link BodySource.BodyFetchException} on any
 * non-2xx response, because it hands back a body meant to be parsed, and a non-2xx body is
 * typically an error page, not data. A {@link ResponseSource} instead RETURNS a non-2xx response —
 * see {@code docs/adr/0030-http-header-current-source.md}: an {@code http-header} source reads a
 * named response header off the final response whatever its status code was (a secured Jenkins
 * answering 403 while still volunteering its version in {@code X-Jenkins} is the motivating case).
 * {@link BodySource.BodyFetchException} is still thrown by a {@link ResponseSource}, but reserved
 * for genuine transport failures (connection refused, DNS failure, redirect-chain rules
 * violated, ...), never for a non-2xx status.
 */
public interface ResponseSource {

    /**
     * @return the fetched/read response.
     * @throws BodySource.BodyFetchException if the response could not be obtained at all (network
     *         error, unreadable connection, ...) — never for a non-2xx status, which is a valid
     *         {@link Response} to return.
     */
    Response fetch();

    /**
     * @param statusCode the HTTP status code of the response, whatever it was — a non-2xx status is
     *                    a normal, valid {@link Response}, not a fetch failure.
     * @param headers     the response headers, keyed by header name exactly as received/configured;
     *                    use {@link #firstHeader(String)} for a case-insensitive lookup rather than
     *                    indexing this map directly.
     */
    record Response(int statusCode, Map<String, List<String>> headers) {

        /**
         * Looks up {@code name} case-insensitively (RFC 9110 section 5.1 — HTTP field names are
         * case-insensitive) and returns the FIRST value of a repeated header, consistent with
         * {@code HttpHeaderCurrentSource}'s (backend, slice 03) extraction semantics, which this
         * validates against.
         *
         * @return the first value of the matching header, or {@link Optional#empty()} if no header
         *         with this name (case-insensitively) is present, or its value list is empty.
         */
        public Optional<String> firstHeader(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                    return Optional.of(entry.getValue().get(0));
                }
            }
            return Optional.empty();
        }
    }
}
