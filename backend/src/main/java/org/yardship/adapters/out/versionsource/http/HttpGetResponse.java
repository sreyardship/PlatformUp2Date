package org.yardship.adapters.out.versionsource.http;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The final response of one {@link HttpTransport#get()}: the URI, status, raw response headers and
 * body of the last hop after every redirect was followed. This is the one shape every HTTP kind on
 * either leg reads from; what each kind does with it — a JSON Pointer into the body, a header
 * lookup on any status, a Prometheus parse only on 2xx, a regex over the body, a release list to
 * select from, a {@code 401} challenge to answer — is that kind's {@link HttpVersionExtractor} or
 * its own loop. The transport takes no position on which status is acceptable.
 *
 * <p>{@link #uri()} is the URI the final hop was fetched from, which differs from the requested
 * one after a redirect. A relative reference in a header of this response (RFC 8288 {@code Link})
 * must be resolved against it, not against what the caller asked for: a page served via a redirect
 * to a canonical endpoint carries that endpoint's own next-page link.
 *
 * <p>{@link #headers()} is deliberately raw (not lower-cased, not de-duplicated), so
 * {@link #firstHeader} does the RFC 9110 §5.1 case-insensitive match and first-value-of-a-repeated-
 * header rule here, once, where a fake response in a unit test exercises it the same way the
 * production map from {@code java.net.http} does.
 */
public record HttpGetResponse(URI uri, int status, Map<String, List<String>> headers, String body) {

    private static final int MAX_DIAGNOSTIC_BODY = 512;

    /** {@code true} for any 2xx status. */
    public boolean isSuccess() {
        return status / 100 == 2;
    }

    /**
     * The body, bounded for a failure message: at most 512 characters, then {@code …[truncated]}.
     * A rate-limit JSON or an HTML login page is the clue to what the endpoint actually returned,
     * but a scrape diagnostic must not carry a whole page. The exception that carries this message
     * may still hold the full body.
     */
    public String truncatedBody() {
        if (body == null) {
            return "null";
        }
        if (body.length() <= MAX_DIAGNOSTIC_BODY) {
            return body;
        }
        return body.substring(0, MAX_DIAGNOSTIC_BODY) + "…[truncated]";
    }

    /**
     * The first value of the header named {@code name}, matched case-insensitively; empty when the
     * header is absent or carries no value.
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
