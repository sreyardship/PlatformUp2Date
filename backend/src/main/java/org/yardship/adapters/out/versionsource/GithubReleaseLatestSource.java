package org.yardship.adapters.out.versionsource;

import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import org.yardship.adapters.out.versionclient.GithubAuthFilter;
import org.yardship.adapters.out.versionclient.GithubReleaseClient;
import org.yardship.adapters.out.versionclient.VersionResponseExceptionMapper;
import org.yardship.core.domain.primitives.Version;
import org.yardship.core.ports.out.LatestVersionSource;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;

/**
 * The {@code github-release} {@link LatestVersionSource}: reads an app's latest (upstream) version
 * from the GitHub Releases API, resolving the release {@code name} into a {@link Version}.
 *
 * <p>A plain (non-CDI), per-app object wrapping a {@link GithubReleaseClient} REST client built for
 * this app's URL. It <b>owns the GitHub auth concern</b>: when a non-blank token is supplied it
 * registers the {@link GithubAuthFilter} so the request carries {@code Authorization: Bearer
 * <token>}; with no/blank token it sends no auth header. The {@link VersionResponseExceptionMapper}
 * is always registered so a non-2xx upstream surfaces as a thrown exception the scrape loop can
 * isolate.
 *
 * <p>The REST client is built lazily on first {@link #version()} so the source can be constructed (by
 * its factory) without a running Quarkus/Arc context.
 */
public class GithubReleaseLatestSource implements LatestVersionSource, Closeable {

    private final String url;
    private final Optional<String> token;
    private GithubReleaseClient client;

    public GithubReleaseLatestSource(String url, Optional<String> token) {
        this.url = url;
        this.token = token;
    }

    @Override
    public Version version() {
        return new Version(client().getLatestRelease().name);
    }

    private GithubReleaseClient client() {
        if (client == null) {
            QuarkusRestClientBuilder builder = QuarkusRestClientBuilder.newBuilder()
                    .baseUri(URI.create(url))
                    .register(VersionResponseExceptionMapper.class);
            token.filter(value -> !value.isBlank())
                    .ifPresent(value -> builder.register(new GithubAuthFilter(value)));
            client = builder.build(GithubReleaseClient.class);
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
