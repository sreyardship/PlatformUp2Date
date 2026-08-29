package org.yardship.adapters.out.versionsource.current.http;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.inject.Vetoed;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.Response;
import org.yardship.adapters.out.versionsource.VersionResponseExceptionMapper;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.security.KeyStore;
import java.util.Optional;

/**
 * Traverses current-version redirects explicitly so credentials are not copied to another origin.
 * Each invocation starts at the configured URL; permanent redirect destinations are not cached.
 */
@Vetoed
final class RedirectingHttpCurrentVersionClient implements HttpCurrentVersionClient, Closeable {
    private static final int MAX_REDIRECTS = 10;

    private final URI initialUri;
    private final Optional<ClientRequestFilter> authFilter;
    private final Optional<KeyStore> trustStore;
    private final boolean insecureSkipTlsVerify;
    private final HttpCurrentVersionClientFactory clientFactory;

    RedirectingHttpCurrentVersionClient(
            URI initialUri, Optional<ClientRequestFilter> authFilter, Optional<KeyStore> trustStore,
            boolean insecureSkipTlsVerify, HttpCurrentVersionClientFactory clientFactory) {
        this.initialUri = initialUri;
        this.authFilter = authFilter;
        this.trustStore = trustStore;
        this.insecureSkipTlsVerify = insecureSkipTlsVerify;
        this.clientFactory = clientFactory;
    }

    @Override
    public JsonNode getCurrentVersion() {
        URI current = initialUri;
        boolean retainAuthorization = true;

        for (int redirectCount = 0; ; redirectCount++) {
            HttpCurrentVersionResponseClient rawClient = clientFactory.buildResponseClient(
                    current, authFilterFor(current, retainAuthorization), trustStore, insecureSkipTlsVerify);
            try (Response response = rawClient.getCurrentVersion(requestPath(current))) {
                if (isSuccessful(response.getStatus())) {
                    return response.readEntity(JsonNode.class);
                }
                if (!isSupportedRedirect(response.getStatus())) {
                    throw new VersionResponseExceptionMapper().toThrowable(response);
                }
                if (redirectCount >= MAX_REDIRECTS) {
                    throw new IllegalStateException(
                            "Too many redirects while reading current version at " + initialUri);
                }

                URI next = redirectTarget(current, response);
                if (isHttpsToHttp(current, next)) {
                    throw new IllegalStateException(
                            "Refusing HTTPS-to-HTTP redirect while reading current version");
                }
                retainAuthorization = retainAuthorization && sameOrigin(current, next);
                current = next;
            } finally {
                closeQuietly(rawClient);
            }
        }
    }

    private Optional<ClientRequestFilter> authFilterFor(URI target, boolean retainAuthorization) {
        if (retainAuthorization && sameOrigin(initialUri, target)) {
            return authFilter;
        }
        return Optional.empty();
    }

    private static String requestPath(URI target) {
        String path = target.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        return path.substring(1) + (target.getRawQuery() == null ? "" : "?" + target.getRawQuery());
    }

    private static URI redirectTarget(URI current, Response response) {
        URI location = response.getLocation();
        if (location == null) {
            throw new IllegalStateException("Current-version redirect did not include a Location header");
        }
        return current.resolve(location);
    }

    private static boolean isSuccessful(int status) {
        return status >= 200 && status < 300;
    }

    private static boolean isSupportedRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static boolean isHttpsToHttp(URI from, URI to) {
        return "https".equalsIgnoreCase(from.getScheme()) && "http".equalsIgnoreCase(to.getScheme());
    }

    private static boolean sameOrigin(URI first, URI second) {
        return equalsIgnoreCase(first.getScheme(), second.getScheme())
                && equalsIgnoreCase(first.getHost(), second.getHost())
                && effectivePort(first) == effectivePort(second);
    }

    private static boolean equalsIgnoreCase(String first, String second) {
        return first != null && first.equalsIgnoreCase(second);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static void closeQuietly(Object client) {
        if (client instanceof Closeable closeable) {
            try {
                closeable.close();
            } catch (IOException ignored) {
                // The response failure is more useful than a cleanup failure.
            }
        }
    }

    @Override
    public void close() throws IOException {
        // Each per-hop REST client is closed immediately after its response is consumed.
    }
}
