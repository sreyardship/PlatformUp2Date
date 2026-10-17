package org.yardship.adapters.out.versionsource.http;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The final response of one {@link HttpTransport#get()}: the status, the raw response headers and
 * the body of the last hop after every redirect was followed. This is the one shape every
 * current-leg HTTP kind reads from; what each kind does with it — a JSON Pointer into the body, a
 * header lookup on any status, a Prometheus parse only on 2xx — is that kind's
 * {@link HttpVersionExtractor}. The transport takes no position on which status is acceptable.
 *
 * <p>{@link #headers()} is deliberately raw (not lower-cased, not de-duplicated), so
 * {@link #firstHeader} does the RFC 9110 §5.1 case-insensitive match and first-value-of-a-repeated-
 * header rule here, once, where a fake response in a unit test exercises it the same way the
 * production map from {@code java.net.http} does.
 */
public record HttpGetResponse(int status, Map<String, List<String>> headers, String body) {

    /** {@code true} for any 2xx status. */
    public boolean isSuccess() {
        return status / 100 == 2;
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
