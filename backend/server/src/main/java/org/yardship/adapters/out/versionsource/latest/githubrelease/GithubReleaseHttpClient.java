package org.yardship.adapters.out.versionsource.latest.githubrelease;

import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import jakarta.enterprise.inject.Vetoed;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.Response;
import org.yardship.adapters.out.versionsource.VersionResponseExceptionMapper;
import org.yardship.adapters.out.versionsource.auth.BearerAuthFilter;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.security.KeyStore;
import java.util.List;
import java.util.Optional;

/**
 * Reads GitHub releases with an explicit redirect policy. Vert.x's default redirect handler copies
 * request headers and permits scheme downgrades, so redirect traversal is kept here rather than
 * enabled globally on the REST client.
 */
@Vetoed
final class GithubReleaseHttpClient implements GithubReleaseClient, Closeable {
    private static final int MAX_REDIRECTS = 10;

    private final URI releasesUri;
    private final Optional<String> token;
    private final Optional<KeyStore> trustStore;
    private final URI initialOrigin;

    GithubReleaseHttpClient(String repositoryUrl, Optional<String> token, int pageSize) {
        this(repositoryUrl, token, pageSize, Optional.empty());
    }

    GithubReleaseHttpClient(
            String repositoryUrl, Optional<String> token, int pageSize, Optional<KeyStore> trustStore) {
        this.releasesUri = appendReleasesPath(URI.create(repositoryUrl), pageSize);
        this.token = token.filter(value -> !value.isBlank());
        this.trustStore = trustStore;
        this.initialOrigin = releasesUri;
    }

    @Override
    public List<GithubReleaseResponseDTO> releases(int ignoredPerPage) {
        URI current = releasesUri;
        boolean retainAuthorization = true;

        for (int redirectCount = 0; ; redirectCount++) {
            GithubReleaseResponseClient rawClient = buildClient(current, retainAuthorization);
            try (Response response = rawClient.releaseArray(requestPath(current))) {
                if (isSuccessful(response.getStatus())) {
                    return response.readEntity(new GenericType<List<GithubReleaseResponseDTO>>() {
                    });
                }

                if (!isSupportedRedirect(response.getStatus())) {
                    throw new VersionResponseExceptionMapper().toThrowable(response);
                }
                if (redirectCount >= MAX_REDIRECTS) {
                    throw new IllegalStateException("Too many redirects while reading GitHub releases at " + releasesUri);
                }

                URI next = redirectTarget(current, response);
                if (isHttpsToHttp(current, next)) {
                    throw new IllegalStateException("Refusing HTTPS-to-HTTP redirect while reading GitHub releases");
                }
                retainAuthorization = retainAuthorization && sameOrigin(current, next);
                current = next;
            } finally {
                closeQuietly(rawClient);
            }
        }
    }

    private GithubReleaseResponseClient buildClient(URI target, boolean retainAuthorization) {
        QuarkusRestClientBuilder builder = QuarkusRestClientBuilder.newBuilder()
                .baseUri(originUri(target))
                .followRedirects(false)
                .property("microprofile.rest.client.disable.default.mapper", true);
        if (trustStore.isPresent()) {
            builder.trustStore(trustStore.get());
        }
        if (retainAuthorization && token.isPresent() && sameOrigin(initialOrigin, target)) {
            builder.register(new BearerAuthFilter(token.get()));
        }
        return builder.build(GithubReleaseResponseClient.class);
    }

    private static URI appendReleasesPath(URI repository, int pageSize) {
        String path = repository.getPath();
        if (!path.endsWith("/")) {
            path += "/";
        }
        path += "releases";
        try {
            return new URI(repository.getScheme(), repository.getRawAuthority(), path,
                    "per_page=" + pageSize, null);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid GitHub releases URL: " + repository, e);
        }
    }

    private static String requestPath(URI target) {
        String path = target.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        return path.substring(1) + (target.getRawQuery() == null ? "" : "?" + target.getRawQuery());
    }

    private static URI originUri(URI target) {
        try {
            return new URI(target.getScheme(), target.getRawAuthority(), "/", null, null);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid GitHub redirect target: " + target, e);
        }
    }

    private static URI redirectTarget(URI current, Response response) {
        URI location = response.getLocation();
        if (location == null) {
            throw new IllegalStateException("GitHub release redirect did not include a Location header");
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
