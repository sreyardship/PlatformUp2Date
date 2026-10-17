package org.yardship.adapters.out.versionsource.http;

import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import org.yardship.adapters.out.versionsource.auth.AuthorizationHeaderRenderer;

import java.net.URI;
import java.net.http.HttpResponse;
import java.security.KeyStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The one HTTP transport every current-leg HTTP kind ({@code http-json}, {@code http-header},
 * {@code http-prometheus}) fetches through. Built once per app from the inputs
 * {@link HttpTransportConfig} resolves out of the config fragment, it owns everything about how the
 * configured GET is made and returns the final response as an {@link HttpGetResponse} — status,
 * headers and body — for the kind's {@link HttpVersionExtractor} to interpret:
 *
 * <ul>
 *   <li>redirects: 301/302/303/307/308 are followed on a bounded chain, the {@code Authorization}
 *       header is retained only same-origin and an HTTPS-to-HTTP downgrade is refused before the
 *       target is contacted (ADR-0029), via {@link RedirectFollowingHttpGet};</li>
 *   <li>TLS: the per-app truststore from {@code ca-cert} (replace, not augment) or the
 *       {@code insecure-skip-tls-verify} escape hatch applies to every hop, never JVM-globally;</li>
 *   <li>auth: the resolved {@link ClientRequestFilter} is rendered into a literal
 *       {@code Authorization} value on EVERY {@link #get()} via {@link AuthorizationHeaderRenderer},
 *       so {@code FileBearerAuthFilter}'s per-request token-file re-read is preserved.</li>
 * </ul>
 *
 * <p>The transport takes no position on which status is acceptable: that is a per-kind decision
 * (a 2xx gate for {@code http-json} and {@code http-prometheus}, none for {@code http-header}) and
 * lives in the extractor. Transport-level failures — connection errors, too many redirects, a
 * refused downgrade — surface as the {@link RuntimeException}s {@link RedirectFollowingHttpGet}
 * throws.
 */
public final class HttpTransport {

    private final URI uri;
    private final Optional<ClientRequestFilter> authFilter;
    private final RedirectFollowingHttpGet httpGet;

    public HttpTransport(
            String url, Optional<ClientRequestFilter> authFilter, Optional<KeyStore> trustStore,
            boolean insecureSkipTlsVerify) {
        this.uri = URI.create(url);
        this.authFilter = authFilter;
        this.httpGet = RedirectFollowingHttpGet.withTls(trustStore, insecureSkipTlsVerify);
    }

    /**
     * Builds the transport for {@code url} from a successful {@link HttpTransportConfig#resolve}.
     *
     * @throws IllegalArgumentException if {@code resolution} carries a failure message — the
     *                                  caller must have mapped that to a {@code FailedCurrentSource}
     *                                  already; building a transport from it is a defect
     */
    public static HttpTransport from(String url, HttpTransportConfig.Resolution resolution) {
        if (resolution.failureMessage().isPresent()) {
            throw new IllegalArgumentException("Cannot build an HTTP transport from a failed resolution: "
                    + resolution.failureMessage().get());
        }
        return new HttpTransport(
                url, resolution.authFilter(), resolution.trustStore(), resolution.insecureSkipTlsVerify());
    }

    /** Issues the configured GET and returns the final (post-redirect) response, whatever its status. */
    public HttpGetResponse get() {
        HttpResponse<String> response = httpGet.get(uri, requestHeaders());
        return new HttpGetResponse(response.statusCode(), response.headers().map(), response.body());
    }

    private Map<String, String> requestHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        authFilter.flatMap(AuthorizationHeaderRenderer::render)
                .ifPresent(value -> headers.put(HttpHeaders.AUTHORIZATION, value));
        return headers;
    }
}
