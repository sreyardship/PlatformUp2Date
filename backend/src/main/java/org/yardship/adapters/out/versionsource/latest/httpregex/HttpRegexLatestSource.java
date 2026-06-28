package org.yardship.adapters.out.versionsource.latest.httpregex;

import org.yardship.adapters.out.versionsource.VersionFetchException;
import org.yardship.core.domain.exceptions.InvalidVersionException;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionValue;
import org.yardship.core.ports.out.LatestVersionSource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code http-regex} {@link LatestVersionSource}: a generic latest source for upstreams without a
 * release API. Fetches {@code url} as text, applies a configured {@code regex}, and parses
 * <b>capture group 1</b> of EVERY match via the app's {@link VersionParser}, returning the LARGEST.
 * "Largest" therefore honours the app's scheme — a calver app picks the largest calendar version.
 *
 * <p>A plain (non-CDI), per-app object. The fetch is content-type agnostic (the Ubuntu feed is plain
 * text, the OpenWRT listing is HTML), so it uses the JDK {@link HttpClient} to GET the body verbatim
 * rather than a typed REST client — there is no JSON to deserialize and no auth to attach
 * (unauthenticated over the public CA, parity with {@code github-release}). A non-2xx response throws
 * a {@link VersionFetchException}; a body with no match, or only unparseable matches, throws — both
 * isolated by the scrape loop as a single app's failure.
 */
public class HttpRegexLatestSource implements LatestVersionSource {

    private final URI uri;
    private final Pattern pattern;
    private final VersionParser parser;
    private final HttpClient http;

    public HttpRegexLatestSource(String url, String regex, VersionParser parser) {
        this.uri = URI.create(url);
        this.pattern = Pattern.compile(regex);
        this.parser = parser;
        this.http = HttpClient.newHttpClient();
    }

    @Override
    public VersionValue version() {
        Matcher matcher = pattern.matcher(fetchBody());
        VersionValue largest = null;
        while (matcher.find()) {
            VersionValue candidate = tryParse(matcher.group(1));
            if (candidate != null && (largest == null || largest.isOlderThan(candidate))) {
                largest = candidate;
            }
        }
        if (largest == null) {
            throw new IllegalStateException(
                    "No parseable version matched regex '" + pattern + "' in the body fetched from " + uri);
        }
        return largest;
    }

    private VersionValue tryParse(String token) {
        try {
            return parser.parse(token);
        } catch (InvalidVersionException ex) {
            return null;
        }
    }

    private String fetchBody() {
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(uri).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new VersionFetchException(
                        "Non-success HTTP status fetching '" + uri + "'",
                        response.statusCode(), response.body());
            }
            return response.body();
        } catch (IOException e) {
            throw new VersionFetchException("Failed to fetch '" + uri + "': " + e.getMessage(), 0, "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VersionFetchException("Interrupted fetching '" + uri + "'", 0, "");
        }
    }
}
