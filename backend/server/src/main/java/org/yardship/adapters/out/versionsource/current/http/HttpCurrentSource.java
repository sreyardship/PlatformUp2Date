package org.yardship.adapters.out.versionsource.current.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.yardship.adapters.out.versionsource.current.http.HttpCurrentVersionClient;
import org.yardship.adapters.out.versionsource.VersionResponseExceptionMapper;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionValue;
import org.yardship.core.ports.out.CurrentVersionSource;

import java.io.Closeable;
import java.io.IOException;

/**
 * The {@code http} {@link CurrentVersionSource}: reads an app's current (deployed) version from an
 * HTTP endpoint's JSON response, extracting it via a configurable JSON Pointer (RFC 6901) — defaulting
 * to {@code /version} so the legacy {@code {"version":"…"}} contract keeps working unconfigured.
 *
 * <p>A plain (non-CDI) POJO holding a ready {@link HttpCurrentVersionClient}, built and injected by its
 * factory via {@code HttpCurrentVersionClientFactory}. This source only does extraction: the
 * {@link VersionResponseExceptionMapper} usage (so a non-2xx upstream surfaces as a thrown
 * exception the scrape loop can isolate), any authentication and TLS configuration, and — per
 * ADR-0029 (see {@code docs/adr/0029-authorization-does-not-cross-redirect-origins.md}) —
 * redirect-following, all live entirely with the client factory / transport now. A configured
 * {@code url} that responds with a supported redirect (301/302/303/307/308) reaches the final JSON
 * body transparently to this class: {@code client.getCurrentVersion()} already returns the FINAL
 * response's body, so extraction here is identical whether or not a redirect occurred.
 */
public class HttpCurrentSource implements CurrentVersionSource, Closeable {

    private static final int MAX_BODY = 512;

    private final HttpCurrentVersionClient client;
    private final String versionKey;
    private final boolean stripPrerelease;
    private final VersionParser parser;

    public HttpCurrentSource(HttpCurrentVersionClient client, String versionKey, boolean stripPrerelease,
                             VersionParser parser) {
        this.client = client;
        this.versionKey = versionKey;
        this.stripPrerelease = stripPrerelease;
        this.parser = parser;
    }

    @Override
    public VersionValue version() {
        JsonNode root = client.getCurrentVersion();
        JsonNode node = root.at(versionKey);
        if (node instanceof MissingNode || !node.isTextual()) {
            // Include the (truncated) upstream body: a 2xx with the version-key absent — e.g. Harbor
            // 2.13+ dropping 'harbor_version' from anonymous /systeminfo — never trips the non-2xx
            // mapper, so the body is the only clue to what the endpoint actually returned.
            throw new IllegalStateException(
                    "The 'http' current source's version-key '" + versionKey
                            + "' did not resolve to a text value in the upstream response. Body: "
                            + truncate(root.toString()));
        }
        VersionValue version = parser.parse(node.textValue());
        return stripPrerelease ? version.withoutPreRelease() : version;
    }

    private static String truncate(String body) {
        if (body.length() <= MAX_BODY) {
            return body;
        }
        return body.substring(0, MAX_BODY) + "…[truncated]";
    }

    @Override
    public void close() throws IOException {
        if (client instanceof Closeable closeable) {
            closeable.close();
        }
    }
}
