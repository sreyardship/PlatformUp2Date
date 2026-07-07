package org.yardship.cli.adapter;

import org.yardship.cli.port.BodySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Driven {@link BodySource} adapter that fetches {@code url} live via the JDK {@link java.net.http.HttpClient},
 * mirroring the fetch half of the production {@code HttpRegexLatestSource} adapter (backend). A
 * non-2xx response or an I/O error is translated to {@link BodySource.BodyFetchException}, which the
 * {@code regex} command maps to {@link org.yardship.cli.outcome.ValidationOutcome.FetchFailed}.
 */
public final class LiveHttpBodySource implements BodySource {

    private final URI uri;
    private final HttpClient http;

    public LiveHttpBodySource(String url) {
        this.uri = URI.create(url);
        this.http = HttpClient.newHttpClient();
    }

    @Override
    public String body() {
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(uri).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new BodyFetchException(
                        "Non-success HTTP status " + response.statusCode() + " fetching '" + uri + "'");
            }
            return response.body();
        } catch (IOException e) {
            throw new BodyFetchException("Failed to fetch '" + uri + "': " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BodyFetchException("Interrupted fetching '" + uri + "'", e);
        }
    }
}
