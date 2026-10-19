package org.yardship.adapters.out.versionsource.latest.githubrelease;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.adapters.out.versionsource.auth.BearerAuthFilter;
import org.yardship.adapters.out.versionsource.http.HttpTransport;
import org.yardship.adapters.out.versionsource.http.HttpTransportConfig;
import org.yardship.adapters.out.versionsource.latest.FailedLatestSource;
import org.yardship.adapters.out.versionsource.latest.HttpLatestSource;
import org.yardship.adapters.out.versionsource.latest.LatestVersionSourceFactory;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.ports.out.LatestVersionSource;

import java.util.Optional;

/**
 * Factory for the {@code github-release} latest-version kind. Discovered as a CDI bean; validates
 * its own STRUCTURAL config fragment fail-fast in {@link #create} — a non-blank {@code repo} in
 * {@code owner/repo} shape and a {@code page-size} within GitHub's {@code per_page} range — both
 * THROWING an {@link IllegalArgumentException}, the declared "this config fragment is unusable"
 * signal {@code VersionSourceResolver} records as a per-app {@code ConfigError} (ADR-0032). It owns
 * the GitHub API host: {@code https://api.github.com} by default,
 * {@link ApplicationConfigLoader.Github#apiBaseUrl()} overriding it (e.g. a WireMock stub in tests).
 *
 * <p>The {@code auth} / {@code ca-cert} / {@code insecure-skip-tls-verify} part of the fragment is
 * delegated to the shared, kind-labelled {@link HttpTransportConfig}; a VALUE-level problem there
 * is mapped to a {@link FailedLatestSource}, never a thrown exception — the single app degrades,
 * the fleet keeps scraping. On success the factory builds the shared {@link HttpTransport} and
 * pairs it with this kind's {@link GithubReleaseVersionExtractor} (ADR-0039, ADR-0040).
 *
 * <p><b>Credential precedence:</b> this factory also OWNS the fleet-wide GitHub token
 * ({@link ApplicationConfigLoader#github()}): an app with no {@code auth} block sends
 * {@code Authorization: Bearer <github.token>} when that token is configured and non-blank, and
 * nothing otherwise. An app's own {@code auth} block wins over the fleet-wide token.
 *
 * <p><b>Exfiltration boundary:</b> the credential is sent to the GitHub API host above, and
 * ADR-0029 guarantees it never survives a redirect onto a different origin. The residual
 * assumption — that {@code github.api-base-url} really is GitHub — lives in configuration, not in a
 * host check here.
 */
@ApplicationScoped
public class GithubReleaseLatestSourceFactory implements LatestVersionSourceFactory {

    private static final String KIND_LABEL = "github-release";
    private static final String DEFAULT_API_BASE_URL = "https://api.github.com";
    private static final int DEFAULT_PAGE_SIZE = 30;

    private final HttpTransportConfig transportConfig = HttpTransportConfig.forLatestSource(KIND_LABEL);
    private final Optional<String> token;
    private final String apiBaseUrl;

    @Inject
    public GithubReleaseLatestSourceFactory(ApplicationConfigLoader configLoader) {
        this(configLoader.github().token(), configLoader.github().apiBaseUrl());
    }

    // Visible for testing: lets tests build the factory without a CDI container / configured token.
    public GithubReleaseLatestSourceFactory(Optional<String> token) {
        this(token, Optional.empty());
    }

    // Visible for testing: lets tests also override the GitHub API host (e.g. to a WireMock stub).
    public GithubReleaseLatestSourceFactory(Optional<String> token, Optional<String> apiBaseUrl) {
        this.token = token.filter(value -> !value.isBlank());
        this.apiBaseUrl = apiBaseUrl.map(String::trim)
                .filter(value -> !value.isBlank())
                .orElse(DEFAULT_API_BASE_URL);
    }

    @Override
    public String type() {
        return KIND_LABEL;
    }

    @Override
    public LatestVersionSource create(ApplicationConfigLoader.VersionSource cfg, VersionParser parser) {
        String repo = cfg.repo()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalArgumentException(
                        "The 'github-release' latest source requires a non-blank 'repo'."));
        long slashCount = repo.chars().filter(ch -> ch == '/').count();
        if (slashCount != 1) {
            throw new IllegalArgumentException(
                    "The 'github-release' latest source's 'repo' must be in 'owner/repo' shape "
                            + "(exactly one '/'); was: " + repo);
        }
        int pageSize = cfg.pageSize().orElse(DEFAULT_PAGE_SIZE);
        if (pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException(
                    "The 'github-release' latest source's 'page-size' must be between 1 and 100 "
                            + "(GitHub's per_page cap); was: " + pageSize);
        }
        String url = apiBaseUrl + "/repos/" + repo + "/releases?per_page=" + pageSize;

        HttpTransportConfig.Resolution resolution = transportConfig.resolve(cfg, url)
                .withAuthFilterIfAbsent(token.map(BearerAuthFilter::new));
        if (resolution.failureMessage().isPresent()) {
            return new FailedLatestSource(resolution.failureMessage().get());
        }

        return new HttpLatestSource(
                HttpTransport.from(url, resolution), new GithubReleaseVersionExtractor(parser));
    }
}
