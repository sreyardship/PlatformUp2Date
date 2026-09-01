package org.yardship.adapters.out.versionsource.current.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.yardship.adapters.out.versionsource.VersionFetchException;
import org.yardship.adapters.out.versionsource.VersionResponseExceptionMapper;
import org.yardship.adapters.out.versionsource.http.RedirectFollowingHttpGet;

import java.net.URI;
import java.net.http.HttpResponse;
import java.security.KeyStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code http} current-version leg's redirect-aware transport, per ADR-0029: fetches the
 * configured URL over {@link RedirectFollowingHttpGet} (rather than a declarative REST-client proxy
 * that does not follow 301/302/303/307/308 with the required credential-origin and TLS-downgrade
 * rules), then decodes the final JSON body.
 *
 * <p>Reuses {@link VersionResponseExceptionMapper} for the exact final-non-2xx-to-
 * {@link VersionFetchException} mapping the rest of the codebase relies on — this class only owns
 * the transport (redirect-following GET + TLS configuration + JSON body decoding), not the
 * failure-mapping policy. Renders whatever {@link ClientRequestFilter} the caller (typically
 * {@code HttpCurrentVersionClientFactory}) supplies into a literal {@code Authorization} header
 * value on every request rather than registering the filter on a JAX-RS client, since
 * {@link RedirectFollowingHttpGet} needs a plain header map, not a filter chain. This preserves
 * {@code FileBearerAuthFilter}'s per-request file re-read semantics: the filter is invoked fresh
 * for every {@link #getCurrentVersion()} call, exactly as a JAX-RS filter would be invoked fresh for
 * every outgoing request.
 *
 * <p>Deliberately does NOT {@code implement HttpCurrentVersionClient}: that interface carries
 * {@code @Path}, and a real (build-time-indexed) class implementing it risks being swept up by
 * Quarkus's JAX-RS/CDI scanning as a resource/bean (see {@code RedirectFollowingGithubReleaseClient}
 * for the same rationale on the {@code latest} leg). {@link HttpCurrentVersionClientFactory} instead
 * wires an instance in via a method reference, a synthetic lambda class invisible to that scanning.
 */
class RedirectFollowingHttpCurrentVersionTransport {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final VersionResponseExceptionMapper EXCEPTION_MAPPER = new VersionResponseExceptionMapper();

    private final URI uri;
    private final Optional<ClientRequestFilter> authFilter;
    private final RedirectFollowingHttpGet httpGet;

    RedirectFollowingHttpCurrentVersionTransport(
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
        authFilter.ifPresent(filter -> {
            String value = renderAuthorizationHeader(filter);
            if (value != null) {
                headers.put(HttpHeaders.AUTHORIZATION, value);
            }
        });
        return headers;
    }

    /**
     * Invokes {@code filter} against a minimal {@link ClientRequestContext} that only supports
     * {@link ClientRequestContext#getHeaders()}, then reads back whatever {@code Authorization}
     * value the filter set. Every auth filter this codebase has ({@link
     * org.yardship.adapters.out.versionsource.auth.BasicAuthFilter}, {@link
     * org.yardship.adapters.out.versionsource.auth.BearerAuthFilter}, {@link
     * org.yardship.adapters.out.versionsource.auth.FileBearerAuthFilter}) only ever calls {@code
     * getHeaders().putSingle(HttpHeaders.AUTHORIZATION, ...)}, so this generic capture works for all
     * of them without this transport needing to know which concrete filter it was handed.
     */
    private static String renderAuthorizationHeader(ClientRequestFilter filter) {
        HeaderCapturingRequestContext context = new HeaderCapturingRequestContext();
        try {
            filter.filter(context);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to render an Authorization header from " + filter, e);
        }
        Object value = context.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        return value == null ? null : value.toString();
    }

    private static JsonNode parseBody(String body) {
        try {
            return OBJECT_MAPPER.readTree(body);
        } catch (Exception e) {
            throw new VersionFetchException(
                    "Failed to parse the 'http' current source's JSON response: " + e.getMessage(), 200, body);
        }
    }

    private static Response toJaxRsResponse(HttpResponse<String> response) {
        return Response.status(response.statusCode()).entity(response.body()).build();
    }

    /**
     * A {@link ClientRequestContext} stub that only implements {@link #getHeaders()}; every other
     * member throws {@link UnsupportedOperationException} because none of the auth filters this
     * codebase has ever call them.
     */
    private static final class HeaderCapturingRequestContext implements ClientRequestContext {

        private final MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();

        @Override
        public MultivaluedMap<String, Object> getHeaders() {
            return headers;
        }

        @Override
        public Object getProperty(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Collection<String> getPropertyNames() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setProperty(String name, Object object) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeProperty(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public URI getUri() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setUri(URI uri) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getMethod() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setMethod(String method) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MultivaluedMap<String, String> getStringHeaders() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getHeaderString(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Date getDate() {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Locale getLanguage() {
            throw new UnsupportedOperationException();
        }

        @Override
        public jakarta.ws.rs.core.MediaType getMediaType() {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.List<jakarta.ws.rs.core.MediaType> getAcceptableMediaTypes() {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.List<java.util.Locale> getAcceptableLanguages() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, jakarta.ws.rs.core.Cookie> getCookies() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean hasEntity() {
            return false;
        }

        @Override
        public Object getEntity() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Class<?> getEntityClass() {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.lang.reflect.Type getEntityType() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setEntity(Object entity) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setEntity(Object entity, java.lang.annotation.Annotation[] annotations,
                jakarta.ws.rs.core.MediaType mediaType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.lang.annotation.Annotation[] getEntityAnnotations() {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.io.OutputStream getEntityStream() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setEntityStream(java.io.OutputStream outputStream) {
            throw new UnsupportedOperationException();
        }

        @Override
        public jakarta.ws.rs.client.Client getClient() {
            throw new UnsupportedOperationException();
        }

        @Override
        public jakarta.ws.rs.core.Configuration getConfiguration() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void abortWith(Response response) {
            throw new UnsupportedOperationException();
        }
    }
}
