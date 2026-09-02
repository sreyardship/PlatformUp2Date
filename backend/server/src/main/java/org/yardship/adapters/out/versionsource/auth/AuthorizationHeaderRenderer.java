package org.yardship.adapters.out.versionsource.auth;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Renders a {@link ClientRequestFilter} into a literal {@code Authorization} header value, for the
 * current-leg HTTP transports ({@code http-json} and {@code http-header}) that talk over a plain
 * {@code java.net.http} client rather than a JAX-RS client with a native filter chain, and so need
 * a header map, not a filter to register.
 *
 * <p>Invokes {@code filter} against a minimal {@link ClientRequestContext} stand-in that only
 * implements {@link ClientRequestContext#getHeaders()}, then reads back whatever
 * {@code Authorization} value the filter set. Every auth filter this codebase has ({@link
 * BasicAuthFilter}, {@link BearerAuthFilter}, {@link FileBearerAuthFilter}) only ever calls
 * {@code getHeaders().putSingle(HttpHeaders.AUTHORIZATION, ...)}, so this generic capture works for
 * all of them without the caller needing to know which concrete filter it was handed. This also
 * preserves {@link FileBearerAuthFilter}'s per-request file re-read semantics: the filter is
 * invoked fresh on every {@link #render} call, exactly as a JAX-RS filter would be invoked fresh
 * for every outgoing request.
 *
 * <p>Extracted so ADR-0029-compliant current-leg transports have exactly one home for this
 * rendering logic rather than near-identical private copies that can drift (see
 * {@code docs/adr/0030-http-header-current-source.md}).
 */
public final class AuthorizationHeaderRenderer {

    private AuthorizationHeaderRenderer() {
    }

    public static Optional<String> render(ClientRequestFilter filter) {
        HeaderCapturingRequestContext context = new HeaderCapturingRequestContext();
        try {
            filter.filter(context);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to render an Authorization header from " + filter, e);
        }
        Object value = context.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        return Optional.ofNullable(value).map(Object::toString);
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
        public Collection<String> getPropertyNames() {
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
        public Date getDate() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Locale getLanguage() {
            throw new UnsupportedOperationException();
        }

        @Override
        public jakarta.ws.rs.core.MediaType getMediaType() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<jakarta.ws.rs.core.MediaType> getAcceptableMediaTypes() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Locale> getAcceptableLanguages() {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Map<String, jakarta.ws.rs.core.Cookie> getCookies() {
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
