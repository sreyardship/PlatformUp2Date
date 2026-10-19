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
 * The one HTTP transport every HTTP kind on both legs fetches through: {@code http-json},
 * {@code http-header} and {@code http-prometheus} on the current leg; {@code github-release},
 * {@code http-regex} and {@code oci-registry} on the latest leg. Built once per app from the inputs
 * {@link HttpTransportConfig} resolves out of the config fragment, it owns everything about how a
 * GET is made and returns the final response as an {@link HttpGetResponse} — URI, status, headers
 * and body — for the kind's {@link HttpVersionExtractor} to interpret:
 *
 * <ul>
 *   <li>redirects: 301/302/303/307/308 are followed on a bounded chain, the {@code Authorization}
 *       header is retained only same-origin and an HTTPS-to-HTTP downgrade is refused before the
 *       target is contacted (ADR-0029), via {@link RedirectFollowingHttpGet};</li>
 *   <li>connection-level failures: a GET whose connection drops or resets before an answer arrives
 *       (the HTTP/1.1 keep-alive close race) is retried once, as RFC 9112 §9.3.1 directs for an
 *       idempotent request; an HTTP error status is an answer and is never retried;</li>
 *   <li>TLS: the per-app truststore from {@code ca-cert} (replace, not augment) or the
 *       {@code insecure-skip-tls-verify} escape hatch applies to every hop, never JVM-globally;</li>
 *   <li>auth: the resolved {@link ClientRequestFilter} is rendered into a literal
 *       {@code Authorization} value on EVERY {@link #get()} via {@link AuthorizationHeaderRenderer},
 *       so {@code FileBearerAuthFilter}'s per-request token-file re-read is preserved.</li>
 * </ul>
 *
 * <p>{@link #get()} issues the configured GET: the app's {@code url} with the app's credential.
 * {@link #get(URI, Optional)} issues a further GET over the same transport — same TLS trust, same
 * redirect and credential rules — at a URI learned from an earlier response, with an explicit
 * credential for that one call. The {@code oci-registry} bearer dance (ADR-0013) learns its token
 * realm from a {@code 401} challenge and its next page from a {@code Link} header, and mints the
 * {@code Bearer} it then carries, so it drives the transport by URI.
 *
 * <p>The transport takes no position on which status is acceptable: that is a per-kind decision
 * (a 2xx gate for {@code http-json} and {@code http-prometheus}, none for {@code http-header}, a
 * {@code 401} read as a challenge for {@code oci-registry}) and lives in the extractor or the
 * kind's own loop. Transport-level failures — connection errors after the one retry, too many
 * redirects, a refused downgrade — surface as the {@link RuntimeException}s
 * {@link RedirectFollowingHttpGet} throws.
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
     *                                  or {@code FailedLatestSource} already; building a transport
     *                                  from it is a defect
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
        return get(uri, authFilter);
    }

    /**
     * Issues a GET at {@code target} — a URI learned from an earlier response, not the configured
     * one — carrying {@code credential} (rendered fresh for this call) instead of the configured
     * credential, over this same transport, and returns the final (post-redirect) response,
     * whatever its status. An empty {@code credential} sends no {@code Authorization} header even
     * when one is configured: the configured credential belongs to the configured URL.
     */
    public HttpGetResponse get(URI target, Optional<ClientRequestFilter> credential) {
        HttpResponse<String> response = httpGet.get(target, requestHeaders(credential));
        return new HttpGetResponse(response.uri(), response.statusCode(), response.headers().map(), response.body());
    }

    private static Map<String, String> requestHeaders(Optional<ClientRequestFilter> credential) {
        Map<String, String> headers = new LinkedHashMap<>();
        credential.flatMap(AuthorizationHeaderRenderer::render)
                .ifPresent(value -> headers.put(HttpHeaders.AUTHORIZATION, value));
        return headers;
    }
}
