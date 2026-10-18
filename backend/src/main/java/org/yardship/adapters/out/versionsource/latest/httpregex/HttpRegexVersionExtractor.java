package org.yardship.adapters.out.versionsource.latest.httpregex;

import org.yardship.adapters.out.versionsource.VersionFetchException;
import org.yardship.adapters.out.versionsource.http.HttpGetResponse;
import org.yardship.adapters.out.versionsource.http.HttpVersionExtractor;
import org.yardship.adapters.out.versionsource.regex.RegexVersionExtractor;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionValue;

/**
 * The {@code http-regex} rule: the final response must be a 2xx, its body is treated as text
 * whatever its content type (the Ubuntu feed is plain text, the OpenWRT listing is HTML), the
 * configured {@code regex} is applied, and <b>capture group 1</b> of EVERY match is parsed via the
 * app's {@link VersionParser}; the LARGEST wins (ADR-0017). "Largest" therefore honours the app's
 * scheme — a calver app picks the largest calendar version.
 *
 * <p>A non-2xx surfaces as a {@link VersionFetchException} naming the status and the URI only —
 * never the body, which for a release page or a vendor listing can be a whole HTML document. A body
 * with no match, or only unparseable matches, throws naming the regex and the URI; both are
 * isolated by the scrape loop as a single app's failure.
 *
 * <p>The extraction machinery itself (pattern compilation/validation, group-1 extraction, tolerance
 * of unparseable candidates, and the largest-wins selection rule) is the leg-neutral
 * {@link RegexVersionExtractor}, shared with the {@code http-header} current kind. A regex that does
 * not compile or has no capture group throws the {@link IllegalArgumentException} from this
 * constructor that the resolver records as a per-app config error (ADR-0032).
 */
public final class HttpRegexVersionExtractor implements HttpVersionExtractor {

    private final String regex;
    private final RegexVersionExtractor extractor;

    public HttpRegexVersionExtractor(String regex, VersionParser parser) {
        this.regex = regex;
        this.extractor = new RegexVersionExtractor("'http-regex' latest source", regex, parser);
    }

    @Override
    public VersionValue extract(HttpGetResponse response) {
        if (!response.isSuccess()) {
            throw new VersionFetchException(
                    "Non-success HTTP status " + response.status() + " fetching '" + response.uri() + "'",
                    response.status(), response.body());
        }
        return extractor.largestIn(response.body())
                .orElseThrow(() -> new IllegalStateException(
                        "No parseable version matched regex '" + regex + "' in the body fetched from "
                                + response.uri()));
    }
}
