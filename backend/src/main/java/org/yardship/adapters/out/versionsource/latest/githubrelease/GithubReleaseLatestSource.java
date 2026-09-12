package org.yardship.adapters.out.versionsource.latest.githubrelease;

import org.yardship.core.domain.exceptions.InvalidVersionException;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import org.yardship.core.domain.primitives.VersionValue;
import org.yardship.core.ports.out.LatestVersionSource;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * The {@code github-release} {@link LatestVersionSource}: reads an app's latest (upstream) version
 * from the GitHub Releases API. "Latest" is the LARGEST semver among the most recently published
 * releases that are not a prerelease and not a draft, selected by each release's {@code tag_name}
 * (not its time-ordered position and not its {@code name}) — see ADR-0010.
 *
 * <p>A plain (non-CDI), per-app object wrapping a {@link GithubReleaseClient} built for this app's
 * URL. The production client (see {@link RedirectFollowingGithubReleaseClient}) fetches over a
 * bounded, credential-origin-aware redirect-following transport per ADR-0029: GitHub's Releases
 * API path for a moved repository can redirect to a {@code /repositories/<id>/} path,
 * and that redirect is followed rather than surfacing as a failure. It <b>owns the GitHub auth
 * concern</b>: when a non-blank token is supplied the request carries
 * {@code Authorization: Bearer <token>} (retained across a redirect only while same-origin, per
 * ADR-0029); with no/blank token it sends no auth header. A non-2xx upstream response (after any
 * supported redirects are followed) surfaces as a thrown exception the scrape loop can isolate.
 *
 * <p><b>Exfiltration boundary:</b> this source sends a GitHub token to the GitHub Releases API,
 * and ADR-0029 additionally guarantees that token never survives a redirect onto a different
 * origin. The auth concern is owned exclusively here — never on the {@code current}
 * ({@code HttpJsonCurrentVersionClient}) leg. The {@code current} leg hits our own deployment
 * endpoints; sending a GitHub token there would be a secret-exfiltration bug.
 *
 * <p><b>Residual assumption:</b> this trusts that {@code latest} always points at GitHub. If a
 * non-GitHub {@code latest} URL is ever configured, the token would be sent to that host (or to a
 * same-origin redirect target of that host). There is no host check here — the assumption lives in
 * configuration, not in the transport.
 *
 * <p>The client is built lazily on first {@link #version()} so the source can be constructed (by
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
    // lazy redirect-following-transport path, so the selection logic (largest version among
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
            RedirectFollowingGithubReleaseClient transport = new RedirectFollowingGithubReleaseClient(url, token);
            client = transport::releases;
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
