package org.yardship.adapters.out.versionsource.current.httpprometheus;

/**
 * The narrow seam {@link HttpPrometheusCurrentSource} depends on to obtain a Prometheus
 * text-exposition body — mirroring how the {@code http-header} current source depends on
 * {@code HttpHeaderFetch} rather than on a concrete transport. In production this is fulfilled by
 * a GET through {@link org.yardship.adapters.out.versionsource.http.RedirectFollowingHttpGet},
 * gated on a 2xx final status (unlike {@code http-header}, this kind refuses a non-2xx response —
 * see {@code docs/adr/0033-http-prometheus-current-source.md}); in unit tests it is a fake
 * supplying a fixed body string, so the source can be tested without a stub server.
 *
 * <p>Deliberately narrower than {@code HttpHeaderFetch}'s {@code HttpHeaderResponse}: the 2xx gate
 * and any {@code VersionFetchException} wrapping live entirely in the production implementation,
 * so this seam hands back a body or throws — there is no status code for
 * {@link HttpPrometheusCurrentSource} to inspect, because by the time it sees a body, the gate has
 * already passed.
 */
@FunctionalInterface
public interface PrometheusBodyFetch {

    String fetch();
}
