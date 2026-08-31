package org.yardship.adapters.out.versionsource.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;

/**
 * A GET-only HTTP transport that follows 301/302/303/307/308 redirects itself, enforcing the
 * ADR-0029 credential-origin contract that the JDK's and Quarkus REST client's built-in
 * redirect-following do not implement:
 *
 * <ul>
 *   <li>bounded hop count — a loop or overlong chain fails fast rather than hanging;</li>
 *   <li>relative and absolute {@code Location} headers are both resolved correctly;</li>
 *   <li>the {@code Authorization} header supplied by the caller is retained on a redirected
 *       request ONLY when scheme, host, and effective port are unchanged (same origin) — a
 *       cross-origin target never receives it;</li>
 *   <li>an HTTPS-to-HTTP downgrade is refused before the HTTP target is ever contacted;</li>
 *   <li>nothing is cached across calls — permanent redirects (301/308) are re-traversed on every
 *       {@link #get(URI, Map)} call, matching a live upstream that may change its target.</li>
 * </ul>
 *
 * <p>Deliberately generic (URI in, headers in, {@link HttpResponse} out) so any adapter making
 * outbound GETs to a source-controlled upstream can reuse it, not just {@code github-release}.
 */
public class RedirectFollowingHttpGet {

    private static final int MAX_REDIRECTS = 10;
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);
    private static final String AUTHORIZATION = "Authorization";
    private static final String LOCATION = "Location";

    private final HttpClient httpClient;

    public RedirectFollowingHttpGet() {
        this(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    // Visible for testing: lets tests inject a client with custom timeouts/executors if ever needed.
    RedirectFollowingHttpGet(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Builds a redirect-following transport whose underlying {@link HttpClient} carries the SAME
     * per-caller TLS configuration on EVERY hop of a redirect chain — the initial request and any
     * redirected one(s) alike, since both are issued by this one client. This closes the gap the
     * default (no-arg) constructor leaves: a plain {@link HttpClient#newHttpClient()}-equivalent
     * default trusts only the JVM's default CA bundle and always verifies hostnames, so a caller
     * pinning a custom CA (or explicitly opting out of TLS verification) via a per-adapter
     * truststore/insecure flag would silently lose that configuration the moment a redirect was
     * followed.
     *
     * <p>{@code trustStore} — when present — REPLACES (not augments) the trust bundle for this
     * client only: a fresh {@link SSLContext} is built from a {@link TrustManagerFactory}
     * initialized with exactly the supplied {@link KeyStore}. {@code insecureSkipTlsVerify} builds a
     * trust-all {@link SSLContext} and disables hostname verification; it is the caller's
     * responsibility (as it already is at the {@code HttpCurrentSourceFactory} boundary) to treat
     * {@code trustStore} and {@code insecureSkipTlsVerify} as mutually exclusive — this method does
     * not itself enforce that. Both {@link Optional#empty()} and {@code insecureSkipTlsVerify=false}
     * yields the JVM default trust bundle with normal hostname verification, i.e. behaviorally
     * identical to the no-arg constructor for a plain HTTP caller.
     */
    public static RedirectFollowingHttpGet withTls(Optional<KeyStore> trustStore, boolean insecureSkipTlsVerify) {
        HttpClient.Builder builder = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER);
        if (insecureSkipTlsVerify) {
            builder.sslContext(trustAllSslContext());
            // A custom (empty) SSLParameters with no endpointIdentificationAlgorithm set overrides
            // HttpClient's own default of "HTTPS", which is what triggers hostname verification —
            // this is the standard way to disable it on java.net.http.HttpClient.
            builder.sslParameters(new SSLParameters());
        } else if (trustStore.isPresent()) {
            builder.sslContext(sslContextTrusting(trustStore.get()));
        }
        return new RedirectFollowingHttpGet(builder.build());
    }

    private static SSLContext sslContextTrusting(KeyStore trustStore) {
        try {
            TrustManagerFactory trustManagerFactory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagerFactory.getTrustManagers(), new SecureRandom());
            return sslContext;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to build an SSLContext from the supplied truststore", e);
        }
    }

    private static SSLContext trustAllSslContext() {
        TrustManager[] trustAllCerts = new TrustManager[] {
                new X509TrustManager() {
                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }

                    @Override
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {
                        // Intentionally accepts any client certificate: insecure-skip-tls-verify.
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {
                        // Intentionally accepts any server certificate: insecure-skip-tls-verify.
                    }
                }
        };
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new SecureRandom());
            return sslContext;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to build a trust-all SSLContext", e);
        }
    }

    /**
     * Issues a GET to {@code uri} with {@code headers}, following supported redirects through a
     * bounded chain, and returns the final (non-redirect) response.
     *
     * @throws TooManyRedirectsException if the chain exceeds the bounded hop count
     * @throws InsecureRedirectException if a redirect would downgrade HTTPS to HTTP
     */
    public HttpResponse<String> get(URI uri, Map<String, String> headers) {
        URI currentUri = uri;
        Map<String, String> currentHeaders = new LinkedHashMap<>(headers);

        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpResponse<String> response = send(currentUri, currentHeaders);

            if (!REDIRECT_STATUSES.contains(response.statusCode())) {
                return response;
            }
            Optional<String> location = response.headers().firstValue(LOCATION);
            if (location.isEmpty()) {
                return response;
            }

            URI targetUri = resolve(currentUri, location.get());
            refuseInsecureDowngrade(currentUri, targetUri);
            if (!isSameOrigin(currentUri, targetUri)) {
                currentHeaders.remove(AUTHORIZATION);
            }
            currentUri = targetUri;
        }

        throw new TooManyRedirectsException(
                "Exceeded " + MAX_REDIRECTS + " redirects fetching '" + uri + "'");
    }

    private HttpResponse<String> send(URI uri, Map<String, String> headers) {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri).GET();
        headers.forEach(requestBuilder::header);
        try {
            return httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
        }
        catch (IOException e) {
            throw new RuntimeException("Failed to fetch '" + uri + "': " + e.getMessage(), e);
        }
        catch (InterruptedException e) {
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

    private static boolean isSameOrigin(URI a, URI b) {
        return schemeEquals(a, b) && hostEquals(a, b) && effectivePort(a) == effectivePort(b);
    }

    private static boolean schemeEquals(URI a, URI b) {
        return a.getScheme() != null && a.getScheme().equalsIgnoreCase(b.getScheme());
    }

    private static boolean hostEquals(URI a, URI b) {
        return a.getHost() != null && a.getHost().equalsIgnoreCase(b.getHost());
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
