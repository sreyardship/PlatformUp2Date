package org.yardship.adapters.out.versionsource;

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
 * HTTP endpoint returning {@code {"version":"…"}}.
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

    private final String url;
    private CurrentVersionClient client;

    public HttpCurrentSource(String url) {
        this.url = url;
    }

    @Override
    public Version version() {
        return new Version(client().getCurrentVersion().version);
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
