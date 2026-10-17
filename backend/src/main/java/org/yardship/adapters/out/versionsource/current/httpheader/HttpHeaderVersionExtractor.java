package org.yardship.adapters.out.versionsource.current.httpheader;

import org.yardship.adapters.out.versionsource.http.HttpGetResponse;
import org.yardship.adapters.out.versionsource.http.HttpVersionExtractor;
import org.yardship.adapters.out.versionsource.regex.RegexVersionExtractor;
import org.yardship.core.domain.exceptions.InvalidVersionException;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionValue;

import java.util.Optional;

/**
 * The {@code http-header} rule: read an app's current version from a named <b>response header</b>
 * rather than from the body. Per {@code docs/adr/0030-http-header-current-source.md} — the binding
 * specification for this kind — its defining, load-bearing behavior is that it reads the
 * configured header off the final response <b>whatever its status code was</b>; the status is
 * consulted only when composing a failure message, never to gate the read. This exists because a
 * secured Jenkins refuses its anonymous top page with 403 and volunteers its version anyway.
 *
 * <p>The header name matches case-insensitively (RFC 9110 §5.1) and a repeated header takes the
 * FIRST value — both via {@link HttpGetResponse#firstHeader}. The value is trimmed. With no
 * {@code regex} configured, the trimmed value is parsed directly; with a {@code regex}, capture
 * group 1 of the FIRST match is used ({@link RegexVersionExtractor#firstIn} — never the largest,
 * since a current version is a single observation, not a selection).
 */
public final class HttpHeaderVersionExtractor implements HttpVersionExtractor {

    private final String url;
    private final String headerName;
    private final Optional<RegexVersionExtractor> regex;
    private final boolean stripPrerelease;
    private final VersionParser parser;

    public HttpHeaderVersionExtractor(String url, String headerName, Optional<RegexVersionExtractor> regex,
            boolean stripPrerelease, VersionParser parser) {
        this.url = url;
        this.headerName = headerName;
        this.regex = regex;
        this.stripPrerelease = stripPrerelease;
        this.parser = parser;
    }

    @Override
    public VersionValue extract(HttpGetResponse response) {
        String trimmedValue = trimmedHeaderValue(response);
        VersionValue version = extractAndParse(trimmedValue);
        return stripPrerelease ? version.withoutPreRelease() : version;
    }

    private String trimmedHeaderValue(HttpGetResponse response) {
        String rawValue = response.firstHeader(headerName)
                .orElseThrow(() -> absentHeader(response.status()));
        String trimmed = rawValue.trim();
        if (trimmed.isEmpty()) {
            throw presentButEmpty(response.status());
        }
        return trimmed;
    }

    private VersionValue extractAndParse(String trimmedValue) {
        if (regex.isPresent()) {
            return regex.get().firstIn(trimmedValue)
                    .orElseThrow(() -> noVersionFound(trimmedValue,
                            "the configured 'regex' matched nothing that parsed as a version"));
        }
        try {
            return parser.parse(trimmedValue);
        } catch (InvalidVersionException ex) {
            throw noVersionFound(trimmedValue, ex.getMessage());
        }
    }

    private IllegalStateException absentHeader(int statusCode) {
        return new IllegalStateException("The 'http-header' current source's header '" + headerName
                + "' was absent from the response (status " + statusCode + ") from '" + url + "'.");
    }

    private IllegalStateException presentButEmpty(int statusCode) {
        return new IllegalStateException("The 'http-header' current source's header '" + headerName
                + "' was present but empty after trimming (status " + statusCode + ") from '" + url
                + "'.");
    }

    private IllegalStateException noVersionFound(String value, String reason) {
        return new IllegalStateException("The 'http-header' current source's header '" + headerName
                + "' had value '" + value + "', which did not yield a parseable version: " + reason
                + " (url '" + url + "').");
    }
}
