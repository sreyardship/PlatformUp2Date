package org.yardship.confcheck.adapter.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.Set;

/**
 * A GET-only HTTP transport that follows 301/302/303/307/308 redirects itself, enforcing the
 * ADR-0029 safe-redirect contract for this module's unauthenticated live fetches — the JDK's
 * built-in redirect-following does not implement:
 *
 * <ul>
 *   <li>bounded hop count — a loop or overlong chain fails fast rather than hanging;</li>
 *   <li>relative and absolute {@code Location} headers are both resolved correctly;</li>
 *   <li>an HTTPS-to-HTTP downgrade is refused before the HTTP target is ever contacted.</li>
 * </ul>
 *
 * <p>This is a small, conf-check-local equivalent of the server module's
 * {@code RedirectFollowingHttpGet}. It is intentionally NOT shared code: {@code :backend:conf-check}
 * must not depend on {@code :backend:server}, and HTTP redirect policy is not domain logic, so it
 * does not belong in {@code :backend:domain} either. There is no credential-stripping concern here
 * (unlike the server-side transport) because every caller in this module is unauthenticated — no
 * {@code Authorization} header is ever sent or carried across a redirect.
 */
public final class RedirectFollowingHttpGet {

    private static final int MAX_REDIRECTS = 10;
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);
    private static final String LOCATION = "Location";

    private final HttpClient httpClient;

    public RedirectFollowingHttpGet() {
        this(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    // Visible for testing.
    RedirectFollowingHttpGet(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Issues a GET to {@code uri}, following supported redirects through a bounded chain, and
     * returns the final (non-redirect) response.
     *
     * @throws TooManyRedirectsException if the chain exceeds the bounded hop count
     * @throws InsecureRedirectException if a redirect would downgrade HTTPS to HTTP
     */
    public HttpResponse<String> get(URI uri) {
        URI currentUri = uri;

        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpResponse<String> response = send(currentUri);

            if (!REDIRECT_STATUSES.contains(response.statusCode())) {
                return response;
            }
            Optional<String> location = response.headers().firstValue(LOCATION);
            if (location.isEmpty()) {
                return response;
            }

            URI targetUri = resolve(currentUri, location.get());
            refuseInsecureDowngrade(currentUri, targetUri);
            currentUri = targetUri;
        }

        throw new TooManyRedirectsException(
                "Exceeded " + MAX_REDIRECTS + " redirects fetching '" + uri + "'");
    }

    private HttpResponse<String> send(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri).GET().build();
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new RuntimeException("Failed to fetch '" + uri + "': " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted fetching '" + uri + "'", e);
        }
    }

    private static URI resolve(URI currentUri, String location) {
        return currentUri.resolve(location);
    }

    private static void refuseInsecureDowngrade(URI from, URI to) {
        if ("https".equalsIgnoreCase(from.getScheme()) && "http".equalsIgnoreCase(to.getScheme())) {
            throw new InsecureRedirectException(from, to);
        }
    }
}
