package org.yardship.adapters.out.versionsource.current.httpheader;

import java.util.List;
import java.util.Map;

/**
 * The narrow, raw view of an HTTP response {@link HttpHeaderCurrentSource} needs: the final
 * status code and the RAW, unnormalized response headers.
 *
 * <p>{@link #headers()} is deliberately raw (not lower-cased, not de-duplicated) so that
 * case-insensitive header-name matching and first-value-of-a-repeated-header are genuine,
 * directly-unit-testable behavior of {@link HttpHeaderCurrentSource} rather than hidden inside a
 * production-only adapter.
 */
public interface HttpHeaderResponse {

    int statusCode();

    Map<String, List<String>> headers();
}
