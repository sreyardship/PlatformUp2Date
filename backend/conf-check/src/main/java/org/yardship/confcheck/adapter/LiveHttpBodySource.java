package org.yardship.confcheck.adapter;

import org.yardship.confcheck.port.BodySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Driven {@link BodySource} adapter that fetches {@code url} live via the JDK {@link java.net.http.HttpClient},
 * mirroring the fetch half of the production {@code HttpRegexLatestSource} adapter (backend). A
 * non-2xx response or an I/O error is translated to {@link BodySource.BodyFetchException}, which the
 * {@code regex} command maps to {@link org.yardship.confcheck.outcome.ValidationOutcome.FetchFailed}.
 */
public final class LiveHttpBodySource implements BodySource {
    private static final int MAX_REDIRECTS = 10;

    private final URI uri;
    private final HttpClient http;

    public LiveHttpBodySource(String url) {
        this.uri = URI.create(url);
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public String body() {
        URI current = uri;
        try {
            for (int redirectCount = 0; ; redirectCount++) {
                HttpResponse<String> response = http.send(
                        HttpRequest.newBuilder(current).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 == 2) {
                    return response.body();
                }
                if (!isSupportedRedirect(response.statusCode())) {
                    throw new BodyFetchException(
                            "Non-success HTTP status " + response.statusCode() + " fetching '" + current + "'");
                }
                if (redirectCount >= MAX_REDIRECTS) {
                    throw new BodyFetchException(
                            "Too many redirects while fetching '" + uri + "'");
                }

                URI next = redirectTarget(current, response);
                if (isHttpsToHttp(current, next)) {
                    throw new BodyFetchException(
                            "Refusing HTTPS-to-HTTP redirect while fetching '" + uri + "'");
                }
                current = next;
            }
        } catch (IOException e) {
            throw new BodyFetchException("Failed to fetch '" + current + "': " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BodyFetchException("Interrupted fetching '" + current + "'", e);
        } catch (IllegalArgumentException e) {
            throw new BodyFetchException(
                    "Invalid redirect target while fetching '" + current + "': " + e.getMessage(), e);
        }
    }

    private static URI redirectTarget(URI current, HttpResponse<String> response) {
        String location = response.headers().firstValue("Location").orElse(null);
        if (location == null) {
            throw new BodyFetchException(
                    "Redirect while fetching '" + current + "' did not include a Location header");
        }
        return current.resolve(location);
    }

    private static boolean isSupportedRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static boolean isHttpsToHttp(URI from, URI to) {
        return "https".equalsIgnoreCase(from.getScheme()) && "http".equalsIgnoreCase(to.getScheme());
    }
}
