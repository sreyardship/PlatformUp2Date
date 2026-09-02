package org.yardship.adapters.out.versionsource.current.httpheader;

import org.yardship.adapters.out.versionsource.regex.RegexVersionExtractor;
import org.yardship.core.domain.exceptions.InvalidVersionException;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionValue;
import org.yardship.core.ports.out.CurrentVersionSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code http-header} {@link CurrentVersionSource}: reads an app's current (deployed) version
 * from a named HTTP <b>response header</b> rather than from a response body. Per
 * {@code docs/adr/0030-http-header-current-source.md} — the binding specification for this kind —
 * this source's defining, load-bearing behavior is that it reads the configured header off the
 * FINAL response <b>whatever its status code was</b>; the status is consulted only when composing
 * a failure message, never to gate the read. This exists because a secured Jenkins refuses its
 * anonymous top page with 403 and volunteers its version anyway.
 *
 * <p>A plain (non-CDI), per-app POJO holding a ready {@link HttpHeaderFetch} — built and injected
 * by its factory — plus the header name, an optional {@link RegexVersionExtractor}, the
 * {@code strip-prerelease} flag, and the app's {@link VersionParser}. {@code Closeable} is
 * unnecessary here: the underlying {@code RedirectFollowingHttpGet} holds no resource needing
 * release, matching {@code HttpRegexLatestSource}.
 *
 * <p>The header name matches case-insensitively (RFC 9110 §5.1 — HTTP field names are
 * case-insensitive by specification). A repeated header takes the FIRST value. The value is
 * trimmed. With no {@code regex} configured, the trimmed value is parsed directly; with a
 * {@code regex}, capture group 1 of the FIRST match is used ({@link RegexVersionExtractor#firstIn}
 * — never the largest, since a current version is a single observation, not a selection).
 */
public class HttpHeaderCurrentSource implements CurrentVersionSource {

    private final HttpHeaderFetch fetch;
    private final String url;
    private final String headerName;
    private final Optional<RegexVersionExtractor> extractor;
    private final boolean stripPrerelease;
    private final VersionParser parser;

    public HttpHeaderCurrentSource(HttpHeaderFetch fetch, String url, String headerName,
            Optional<RegexVersionExtractor> extractor, boolean stripPrerelease, VersionParser parser) {
        this.fetch = fetch;
        this.url = url;
        this.headerName = headerName;
        this.extractor = extractor;
        this.stripPrerelease = stripPrerelease;
        this.parser = parser;
    }

    @Override
    public VersionValue version() {
        HttpHeaderResponse response = fetch.fetch();
        String trimmedValue = trimmedHeaderValue(response);
        VersionValue version = extractAndParse(trimmedValue);
        return stripPrerelease ? version.withoutPreRelease() : version;
    }

    private String trimmedHeaderValue(HttpHeaderResponse response) {
        String rawValue = firstHeaderValue(response.headers())
                .orElseThrow(() -> absentHeader(response.statusCode()));
        String trimmed = rawValue.trim();
        if (trimmed.isEmpty()) {
            throw presentButEmpty(response.statusCode());
        }
        return trimmed;
    }

    private Optional<String> firstHeaderValue(Map<String, List<String>> headers) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(headerName) && !entry.getValue().isEmpty()) {
                return Optional.of(entry.getValue().get(0));
            }
        }
        return Optional.empty();
    }

    private VersionValue extractAndParse(String trimmedValue) {
        if (extractor.isPresent()) {
            return extractor.get().firstIn(trimmedValue)
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
