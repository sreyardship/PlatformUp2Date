package org.yardship.adapters.out.versionsource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.yardship.adapters.out.versionclient.CurrentVersionClient;
import org.yardship.adapters.out.versionclient.VersionResponseExceptionMapper;
import org.yardship.core.domain.primitives.Version;
import org.yardship.core.ports.out.CurrentVersionSource;

import java.io.Closeable;
import java.io.IOException;

/**
 * The {@code http} {@link CurrentVersionSource}: reads an app's current (deployed) version from an
 * HTTP endpoint's JSON response, extracting it via a configurable JSON Pointer (RFC 6901) — defaulting
 * to {@code /version} so the legacy {@code {"version":"…"}} contract keeps working unconfigured.
 *
 * <p>A plain (non-CDI) POJO holding a ready {@link CurrentVersionClient}, built and injected by its
 * factory via {@code CurrentVersionClientFactory}. This source only does extraction: the
 * {@link VersionResponseExceptionMapper} registration (so a non-2xx upstream surfaces as a thrown
 * exception the scrape loop can isolate) lives entirely with the client factory now — this slice
 * wires no authentication onto that client.
 */
public class HttpCurrentSource implements CurrentVersionSource, Closeable {

    private static final int MAX_BODY = 512;

    private final CurrentVersionClient client;
    private final String versionKey;
    private final boolean stripPrerelease;

    public HttpCurrentSource(CurrentVersionClient client, String versionKey, boolean stripPrerelease) {
        this.client = client;
        this.versionKey = versionKey;
        this.stripPrerelease = stripPrerelease;
    }

    @Override
    public Version version() {
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
        Version version = new Version(node.textValue());
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
