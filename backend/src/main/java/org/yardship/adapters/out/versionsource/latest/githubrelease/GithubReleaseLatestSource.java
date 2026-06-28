package org.yardship.adapters.out.versionsource.latest.githubrelease;

import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import org.yardship.adapters.out.versionsource.auth.BearerAuthFilter;
import org.yardship.adapters.out.versionsource.latest.githubrelease.GithubReleaseClient;
import org.yardship.adapters.out.versionsource.latest.githubrelease.GithubReleaseResponseDTO;
import org.yardship.adapters.out.versionsource.VersionResponseExceptionMapper;
import org.yardship.core.domain.exceptions.InvalidVersionException;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import org.yardship.core.domain.primitives.VersionValue;
import org.yardship.core.ports.out.LatestVersionSource;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * The {@code github-release} {@link LatestVersionSource}: reads an app's latest (upstream) version
 * from the GitHub Releases API. "Latest" is the LARGEST semver among the most recently published
 * releases that are not a prerelease and not a draft, selected by each release's {@code tag_name}
 * (not its time-ordered position and not its {@code name}) — see ADR-0010.
 *
 * <p>A plain (non-CDI), per-app object wrapping a {@link GithubReleaseClient} REST client built for
 * this app's URL. It <b>owns the GitHub auth concern</b>: when a non-blank token is supplied it
 * registers the shared, scheme-generic {@link BearerAuthFilter} so the request carries
 * {@code Authorization: Bearer <token>}; with no/blank token it sends no auth header. The
 * {@link VersionResponseExceptionMapper} is always registered so a non-2xx upstream surfaces as a
 * thrown exception the scrape loop can isolate.
 *
 * <p><b>Exfiltration boundary:</b> this source sends a GitHub token to the GitHub Releases API.
 * The {@link BearerAuthFilter} is registered exclusively here — the source that owns the GitHub
 * auth concern — and never on the {@code current} ({@code HttpCurrentVersionClient}) leg. The
 * {@code current} leg hits our own deployment endpoints; sending a GitHub token there would be a
 * secret-exfiltration bug.
 *
 * <p><b>Residual assumption:</b> this trusts that {@code latest} always points at GitHub. If a
 * non-GitHub {@code latest} URL is ever configured, the token would be sent to that host. There
 * is no host check here — the assumption lives in configuration, not in the filter.
 *
 * <p>The REST client is built lazily on first {@link #version()} so the source can be constructed (by
 * its factory) without a running Quarkus/Arc context.
 */
public class GithubReleaseLatestSource implements LatestVersionSource, Closeable {

    /**
     * Default {@code per_page} sent when this source is built via the 2-arg constructor (the
     * production/CDI path without an explicit page-size), matching the factory's own default.
     */
    private static final int DEFAULT_PAGE_SIZE = 30;

    private final String url;
    private final Optional<String> token;
    private final int pageSize;
    private final VersionParser parser;
    private GithubReleaseClient client;

    public GithubReleaseLatestSource(String url, Optional<String> token, VersionParser parser) {
        this(url, token, DEFAULT_PAGE_SIZE, parser);
    }

    public GithubReleaseLatestSource(String url, Optional<String> token, int pageSize, VersionParser parser) {
        this.url = url;
        this.token = token;
        this.pageSize = pageSize;
        this.parser = parser;
    }

    // Visible for testing: lets unit tests inject a fake GithubReleaseClient directly, bypassing the
    // lazy QuarkusRestClientBuilder path, so the selection logic (largest version among
    // non-prerelease/non-draft releases, by tag_name) can be unit-tested without HTTP/Quarkus. The
    // fake ignores the perPage argument, so the exact value passed here is inconsequential. Defaults
    // to a SEMVER parser, matching every GitHub-released app today.
    public GithubReleaseLatestSource(GithubReleaseClient client) {
        this.url = null;
        this.token = Optional.empty();
        this.pageSize = DEFAULT_PAGE_SIZE;
        this.parser = new VersionParser(VersionScheme.SEMVER);
        this.client = client;
    }

    @Override
    public VersionValue version() {
        List<GithubReleaseResponseDTO> releases = client().releases(pageSize);
        return releases.stream()
                .filter(release -> !release.prerelease && !release.draft)
                .map(this::tryParseVersion)
                .flatMap(Optional::stream)
                .reduce((current, candidate) -> current.isOlderThan(candidate) ? candidate : current)
                .orElseThrow(() -> new IllegalStateException(
                        "No release with a parseable, non-prerelease, non-draft tag_name was found at "
                                + url));
    }

    private Optional<VersionValue> tryParseVersion(GithubReleaseResponseDTO release) {
        try {
            return Optional.of(parser.parse(release.tagName));
        }
        catch (InvalidVersionException ex) {
            return Optional.empty();
        }
    }

    private GithubReleaseClient client() {
        if (client == null) {
            QuarkusRestClientBuilder builder = QuarkusRestClientBuilder.newBuilder()
                    .baseUri(URI.create(url))
                    .register(VersionResponseExceptionMapper.class);
            token.filter(value -> !value.isBlank())
                    .ifPresent(value -> builder.register(new BearerAuthFilter(value)));
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
