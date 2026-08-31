package org.yardship.adapters.out.versionsource.latest.httpregex;

import org.yardship.adapters.out.versionsource.VersionFetchException;
import org.yardship.adapters.out.versionsource.http.InsecureRedirectException;
import org.yardship.adapters.out.versionsource.http.RedirectFollowingHttpGet;
import org.yardship.adapters.out.versionsource.http.TooManyRedirectsException;
import org.yardship.core.domain.exceptions.InvalidVersionException;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionValue;
import org.yardship.core.ports.out.LatestVersionSource;

import java.net.URI;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code http-regex} {@link LatestVersionSource}: a generic latest source for upstreams without a
 * release API. Fetches {@code url} as text, applies a configured {@code regex}, and parses
 * <b>capture group 1</b> of EVERY match via the app's {@link VersionParser}, returning the LARGEST.
 * "Largest" therefore honours the app's scheme — a calver app picks the largest calendar version.
 *
 * <p>A plain (non-CDI), per-app object. The fetch is content-type agnostic (the Ubuntu feed is plain
 * text, the OpenWRT listing is HTML), so it uses {@link RedirectFollowingHttpGet} to GET the body
 * verbatim rather than a typed REST client — there is no JSON to deserialize and no auth to attach
 * (unauthenticated over the public CA, parity with {@code github-release}). Redirects (301/302/303/
 * 307/308) are followed to their final response before the regex is applied, per ADR-0029. A non-2xx
 * response on the final response throws a {@link VersionFetchException}; a body with no match, or
 * only unparseable matches, throws — both isolated by the scrape loop as a single app's failure.
 */
public class HttpRegexLatestSource implements LatestVersionSource {

    private final URI uri;
    private final Pattern pattern;
    private final VersionParser parser;
    private final RedirectFollowingHttpGet http;

    public HttpRegexLatestSource(String url, String regex, VersionParser parser) {
        this.uri = URI.create(url);
        this.pattern = Pattern.compile(regex);
        this.parser = parser;
        this.http = new RedirectFollowingHttpGet();
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
        HttpResponse<String> response = get();
        if (response.statusCode() / 100 != 2) {
            throw new VersionFetchException(
                    "Non-success HTTP status fetching '" + uri + "'",
                    response.statusCode(), response.body());
        }
        return response.body();
    }

    private HttpResponse<String> get() {
        try {
            return http.get(uri, Map.of());
        } catch (InsecureRedirectException | TooManyRedirectsException e) {
            throw new VersionFetchException("Failed to fetch '" + uri + "': " + e.getMessage(), 0, "");
        } catch (RuntimeException e) {
            throw new VersionFetchException("Failed to fetch '" + uri + "': " + e.getMessage(), 0, "");
        }
    }
}
