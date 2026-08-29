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
    private static final int MAX_REDIRECTS = 10;

    private final URI uri;
    private final Pattern pattern;
    private final VersionParser parser;
    private final HttpClient http;

    public HttpRegexLatestSource(String url, String regex, VersionParser parser) {
        this.uri = URI.create(url);
        this.pattern = Pattern.compile(regex);
        this.parser = parser;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
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
                    throw new VersionFetchException(
                            "Non-success HTTP status fetching '" + current + "'",
                            response.statusCode(), response.body());
                }
                if (redirectCount >= MAX_REDIRECTS) {
                    throw new VersionFetchException(
                            "Too many redirects while fetching '" + uri + "'", 0, "");
                }

                URI next = redirectTarget(current, response);
                if (isHttpsToHttp(current, next)) {
                    throw new VersionFetchException(
                            "Refusing HTTPS-to-HTTP redirect while fetching '" + uri + "'", 0, "");
                }
                current = next;
            }
        } catch (IOException e) {
            throw new VersionFetchException("Failed to fetch '" + current + "': " + e.getMessage(), 0, "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VersionFetchException("Interrupted fetching '" + current + "'", 0, "");
        } catch (IllegalArgumentException e) {
            throw new VersionFetchException(
                    "Invalid redirect target while fetching '" + current + "': " + e.getMessage(), 0, "");
        }
    }

    private static URI redirectTarget(URI current, HttpResponse<String> response) {
        String location = response.headers().firstValue("Location").orElse(null);
        if (location == null) {
            throw new VersionFetchException(
                    "Redirect while fetching '" + current + "' did not include a Location header", 0, "");
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
