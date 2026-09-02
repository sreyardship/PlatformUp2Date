package org.yardship.adapters.out.versionsource.current.httpheader;

import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import org.yardship.adapters.out.versionsource.auth.AuthorizationHeaderRenderer;
import org.yardship.adapters.out.versionsource.http.RedirectFollowingHttpGet;

import java.net.URI;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Production {@link HttpHeaderFetch}: GETs {@code uri} through {@link RedirectFollowingHttpGet} —
 * ADR-0029's redirect rules and the per-caller TLS configuration built by
 * {@code HttpCurrentTransportConfig} apply unchanged, and {@link RedirectFollowingHttpGet} itself
 * is untouched. The body is never read; only the final response's status code and headers are
 * surfaced, per ADR-0030.
 *
 * <p>When {@code authFilter} is present, the rendered {@code Authorization} value is attached to
 * every request — including a re-issued one after a redirect, since a fresh header map is built
 * on every {@link #fetch()} call. Rendering is delegated to {@link AuthorizationHeaderRenderer},
 * shared with the {@code http} kind's transport ({@code RedirectFollowingHttpCurrentVersionTransport})
 * so the two current-leg HTTP transports do not carry two copies of the same rendering logic. This
 * preserves {@code FileBearerAuthFilter}'s per-request file re-read semantics: a {@code
 * token-file} credential is re-read from disk on every scrape, exactly as it is for the {@code
 * http} kind.
 */
class RedirectFollowingHttpHeaderFetch implements HttpHeaderFetch {

    private final RedirectFollowingHttpGet http;
    private final URI uri;
    private final Optional<ClientRequestFilter> authFilter;

    RedirectFollowingHttpHeaderFetch(
            RedirectFollowingHttpGet http, URI uri, Optional<ClientRequestFilter> authFilter) {
        this.http = http;
        this.uri = uri;
        this.authFilter = authFilter;
    }

    @Override
    public HttpHeaderResponse fetch() {
        HttpResponse<String> response = http.get(uri, requestHeaders());
        return new RawHttpHeaderResponse(response);
    }

    private Map<String, String> requestHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        authFilter.flatMap(AuthorizationHeaderRenderer::render)
                .ifPresent(value -> headers.put(HttpHeaders.AUTHORIZATION, value));
        return headers;
    }

    private record RawHttpHeaderResponse(HttpResponse<String> response) implements HttpHeaderResponse {

        @Override
        public int statusCode() {
            return response.statusCode();
        }

        @Override
        public Map<String, List<String>> headers() {
            return response.headers().map();
        }
    }
}
