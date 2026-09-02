package org.yardship.adapters.out.versionsource.current.httpjson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.yardship.adapters.out.versionsource.VersionFetchException;
import org.yardship.adapters.out.versionsource.VersionResponseExceptionMapper;
import org.yardship.adapters.out.versionsource.auth.AuthorizationHeaderRenderer;
import org.yardship.adapters.out.versionsource.http.RedirectFollowingHttpGet;

import java.net.URI;
import java.net.http.HttpResponse;
import java.security.KeyStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code http-json} current-version leg's redirect-aware transport, per ADR-0029: fetches the
 * configured URL over {@link RedirectFollowingHttpGet} (rather than a declarative REST-client proxy
 * that does not follow 301/302/303/307/308 with the required credential-origin and TLS-downgrade
 * rules), then decodes the final JSON body.
 *
 * <p>Reuses {@link VersionResponseExceptionMapper} for the exact final-non-2xx-to-
 * {@link VersionFetchException} mapping the rest of the codebase relies on — this class only owns
 * the transport (redirect-following GET + TLS configuration + JSON body decoding), not the
 * failure-mapping policy. Renders whatever {@link ClientRequestFilter} the caller (typically
 * {@code HttpJsonCurrentVersionClientFactory}) supplies into a literal {@code Authorization} header
 * value on every request via {@link AuthorizationHeaderRenderer}, shared with the {@code
 * http-header} kind's transport, rather than registering the filter on a JAX-RS client, since
 * {@link RedirectFollowingHttpGet} needs a plain header map, not a filter chain. This preserves
 * {@code FileBearerAuthFilter}'s per-request file re-read semantics: the filter is invoked fresh
 * for every {@link #getCurrentVersion()} call, exactly as a JAX-RS filter would be invoked fresh for
 * every outgoing request.
 *
 * <p>Deliberately does NOT {@code implement HttpJsonCurrentVersionClient}: that interface carries
 * {@code @Path}, and a real (build-time-indexed) class implementing it risks being swept up by
 * Quarkus's JAX-RS/CDI scanning as a resource/bean (see {@code RedirectFollowingGithubReleaseClient}
 * for the same rationale on the {@code latest} leg). {@link HttpJsonCurrentVersionClientFactory} instead
 * wires an instance in via a method reference, a synthetic lambda class invisible to that scanning.
 */
class RedirectFollowingHttpJsonCurrentVersionTransport {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final VersionResponseExceptionMapper EXCEPTION_MAPPER = new VersionResponseExceptionMapper();

    private final URI uri;
    private final Optional<ClientRequestFilter> authFilter;
    private final RedirectFollowingHttpGet httpGet;

    RedirectFollowingHttpJsonCurrentVersionTransport(
            String url, Optional<ClientRequestFilter> authFilter, Optional<KeyStore> trustStore,
            boolean insecureSkipTlsVerify) {
        this.uri = URI.create(url);
        this.authFilter = authFilter;
        this.httpGet = RedirectFollowingHttpGet.withTls(trustStore, insecureSkipTlsVerify);
    }

    JsonNode getCurrentVersion() {
        HttpResponse<String> response = httpGet.get(uri, requestHeaders());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw EXCEPTION_MAPPER.toThrowable(toJaxRsResponse(response));
        }
        return parseBody(response.body());
    }

    private Map<String, String> requestHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        authFilter.flatMap(AuthorizationHeaderRenderer::render)
                .ifPresent(value -> headers.put(HttpHeaders.AUTHORIZATION, value));
        return headers;
    }

    private static JsonNode parseBody(String body) {
        try {
            return OBJECT_MAPPER.readTree(body);
        } catch (Exception e) {
            throw new VersionFetchException(
                    "Failed to parse the 'http-json' current source's JSON response: " + e.getMessage(), 200, body);
        }
    }

    private static Response toJaxRsResponse(HttpResponse<String> response) {
        return Response.status(response.statusCode()).entity(response.body()).build();
    }
}
