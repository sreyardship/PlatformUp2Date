package org.yardship.adapters.out.versionsource.current.httpprometheus;

import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import org.yardship.adapters.out.versionsource.VersionFetchException;
import org.yardship.adapters.out.versionsource.auth.AuthorizationHeaderRenderer;
import org.yardship.adapters.out.versionsource.http.RedirectFollowingHttpGet;

import java.net.URI;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Production {@link PrometheusBodyFetch}: GETs {@code uri} through {@link RedirectFollowingHttpGet}
 * — ADR-0029's redirect rules and the per-caller TLS configuration built by
 * {@code HttpTransportConfig} apply unchanged, and {@link RedirectFollowingHttpGet} itself is
 * untouched. Unlike {@code RedirectFollowingHttpHeaderFetch}, this fetch <b>gates on a 2xx final
 * response</b> — per ADR-0033, the metrics body IS the resource here, so a non-2xx response (a
 * login page, a proxy error page) must fail the read rather than be handed to the parser.
 *
 * <p>When {@code authFilter} is present, the rendered {@code Authorization} value is attached to
 * every request — including a re-issued one after a redirect, since a fresh header map is built on
 * every {@link #fetch()} call. Rendering is delegated to {@link AuthorizationHeaderRenderer},
 * shared with the {@code http-header} and {@code http-json} kinds' transports so the current-leg
 * HTTP transports do not carry multiple copies of the same rendering logic. This preserves
 * {@code FileBearerAuthFilter}'s per-request file re-read semantics: a {@code token-file}
 * credential is re-read from disk on every scrape.
 *
 * <p>No failure message this class raises ever embeds the response body — following
 * {@code http-regex}'s own gate, which names the URI and status only, per ADR-0033.
 */
class RedirectFollowingPrometheusBodyFetch implements PrometheusBodyFetch {

    private final RedirectFollowingHttpGet http;
    private final URI uri;
    private final Optional<ClientRequestFilter> authFilter;

    RedirectFollowingPrometheusBodyFetch(
            RedirectFollowingHttpGet http, URI uri, Optional<ClientRequestFilter> authFilter) {
        this.http = http;
        this.uri = uri;
        this.authFilter = authFilter;
    }

    @Override
    public String fetch() {
        HttpResponse<String> response = http.get(uri, requestHeaders());
        if (response.statusCode() / 100 != 2) {
            throw new VersionFetchException("The 'http-prometheus' current source got a non-success "
                    + "HTTP status " + response.statusCode() + " fetching '" + uri + "'.",
                    response.statusCode(), "");
        }
        return response.body();
    }

    private Map<String, String> requestHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        authFilter.flatMap(AuthorizationHeaderRenderer::render)
                .ifPresent(value -> headers.put(HttpHeaders.AUTHORIZATION, value));
        return headers;
    }
}
