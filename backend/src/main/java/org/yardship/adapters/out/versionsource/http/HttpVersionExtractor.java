package org.yardship.adapters.out.versionsource.http;

import org.yardship.core.domain.primitives.VersionValue;

/**
 * One kind's rule for turning the final response of an {@link HttpTransport#get()} into a
 * version. The transport decides how the GET is made (redirects, credential rules, TLS, auth
 * rendering); an extractor decides what the response means — including which statuses are
 * acceptable, since that differs per kind: {@code http-json}, {@code http-prometheus},
 * {@code github-release} and {@code http-regex} refuse a non-2xx, {@code http-header} reads its
 * header off any status (ADR-0030). Current and latest kinds alike implement it (ADR-0039, ADR-0040).
 *
 * <p>Pure: no I/O, so each kind's rule is unit-tested by handing it a hand-built
 * {@link HttpGetResponse}.
 */
@FunctionalInterface
public interface HttpVersionExtractor {

    /**
     * @throws RuntimeException with a clear, operator-facing message when the response does not
     *                          yield a version for this kind — the scrape loop isolates it per app
     */
    VersionValue extract(HttpGetResponse response);
}
