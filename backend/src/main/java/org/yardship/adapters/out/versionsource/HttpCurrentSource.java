package org.yardship.adapters.out.versionsource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import org.yardship.adapters.out.versionclient.CurrentVersionClient;
import org.yardship.adapters.out.versionclient.VersionResponseExceptionMapper;
import org.yardship.core.domain.primitives.Version;
import org.yardship.core.ports.out.CurrentVersionSource;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;

/**
 * The {@code http} {@link CurrentVersionSource}: reads an app's current (deployed) version from an
 * HTTP endpoint's JSON response, extracting it via a configurable JSON Pointer (RFC 6901) — defaulting
 * to {@code /version} so the legacy {@code {"version":"…"}} contract keeps working unconfigured.
 *
 * <p>A plain (non-CDI), per-app object wrapping a {@link CurrentVersionClient} REST client built
 * for this app's URL. It registers the {@link VersionResponseExceptionMapper} so a non-2xx upstream
 * surfaces as a thrown exception the scrape loop can isolate — and <b>never</b> registers the
 * GitHub auth filter: the current leg hits our own deployment endpoints, where a GitHub token would
 * be a secret-exfiltration bug.
 *
 * <p>The REST client is built lazily on first {@link #version()} so the source can be constructed (by
 * its factory) without a running Quarkus/Arc context.
 */
public class HttpCurrentSource implements CurrentVersionSource, Closeable {

    private static final int MAX_BODY = 512;

    private final String url;
    private final String versionKey;
    private final boolean stripPrerelease;
    private CurrentVersionClient client;

    public HttpCurrentSource(String url, String versionKey, boolean stripPrerelease) {
        this.url = url;
        this.versionKey = versionKey;
        this.stripPrerelease = stripPrerelease;
    }

    @Override
    public Version version() {
        JsonNode root = client().getCurrentVersion();
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

    private CurrentVersionClient client() {
        if (client == null) {
            client = QuarkusRestClientBuilder.newBuilder()
                    .baseUri(URI.create(url))
                    .register(VersionResponseExceptionMapper.class)
                    .build(CurrentVersionClient.class);
        }
        return client;
    }

    @Override
    public void close() throws IOException {
        if (client instanceof Closeable closeable) {
            closeable.close();
        }
    }
}
