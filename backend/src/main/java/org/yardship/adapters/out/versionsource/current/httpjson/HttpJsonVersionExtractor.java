package org.yardship.adapters.out.versionsource.current.httpjson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.yardship.adapters.out.versionsource.VersionFetchException;
import org.yardship.adapters.out.versionsource.http.HttpGetResponse;
import org.yardship.adapters.out.versionsource.http.HttpVersionExtractor;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionValue;

/**
 * The {@code http-json} rule: the final response must be a 2xx, its body is decoded as JSON, and
 * a configurable JSON Pointer (RFC 6901) — defaulting to {@code /version} at the factory, so the
 * legacy {@code {"version":"…"}} contract keeps working unconfigured — must resolve to a text value.
 *
 * <p>A non-2xx surfaces as a {@link VersionFetchException} carrying the status and a truncated
 * copy of the body, so the per-app scrape loop only has to log {@code e.getMessage()} to get an
 * informative diagnostic (a rate-limit JSON, an HTML login page). A 2xx whose body is not JSON, or
 * whose pointer does not resolve, likewise names the (truncated) body: a Harbor 2.13+ dropping
 * {@code harbor_version} from anonymous {@code /systeminfo} never trips the status gate, so the
 * body is the only clue to what the endpoint actually returned.
 */
public final class HttpJsonVersionExtractor implements HttpVersionExtractor {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String versionKey;
    private final boolean stripPrerelease;
    private final VersionParser parser;

    public HttpJsonVersionExtractor(String versionKey, boolean stripPrerelease, VersionParser parser) {
        this.versionKey = versionKey;
        this.stripPrerelease = stripPrerelease;
        this.parser = parser;
    }

    @Override
    public VersionValue extract(HttpGetResponse response) {
        if (!response.isSuccess()) {
            throw new VersionFetchException(
                    "HTTP " + response.status() + " response: " + response.truncatedBody(),
                    response.status(), response.body());
        }
        JsonNode root = parseBody(response);
        JsonNode node = root.at(versionKey);
        if (node instanceof MissingNode || !node.isTextual()) {
            throw new IllegalStateException(
                    "The 'http-json' current source's version-key '" + versionKey
                            + "' did not resolve to a text value in the upstream response. Body: "
                            + response.truncatedBody());
        }
        VersionValue version = parser.parse(node.textValue());
        return stripPrerelease ? version.withoutPreRelease() : version;
    }

    private static JsonNode parseBody(HttpGetResponse response) {
        try {
            return OBJECT_MAPPER.readTree(response.body());
        } catch (Exception e) {
            throw new VersionFetchException(
                    "Failed to parse the 'http-json' current source's JSON response: " + e.getMessage(),
                    response.status(), response.body());
        }
    }
}
