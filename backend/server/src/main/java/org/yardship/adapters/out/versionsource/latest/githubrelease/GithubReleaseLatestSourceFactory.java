package org.yardship.adapters.out.versionsource.latest.githubrelease;
import org.yardship.adapters.out.versionsource.latest.LatestVersionSourceFactory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.ports.out.LatestVersionSource;

import java.util.Optional;

/**
 * Factory for the {@code github-release} latest-version kind. Discovered as a CDI bean; validates
 * its own config fragment ({@code github-release} requires a non-blank {@code repo} in
 * {@code owner/repo} shape) and constructs a per-app {@link GithubReleaseLatestSource}.
 *
 * <p>This factory OWNS the GitHub token concern: it holds the configured token (sourced from
 * {@link ApplicationConfigLoader#github()} on the CDI path) and hands it to every source it builds,
 * so the auth decision never leaks to the resolver or the core.
 *
 * <p>It also owns the GitHub API host: the real {@code https://api.github.com} is the default, but
 * {@link ApplicationConfigLoader.Github#apiBaseUrl()} lets it be overridden (e.g. by tests pointing
 * at a local WireMock stub) without exposing a per-app host field.
 */
@ApplicationScoped
public class GithubReleaseLatestSourceFactory implements LatestVersionSourceFactory {

    private static final String DEFAULT_API_BASE_URL = "https://api.github.com";

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
        this.token = token;
        this.apiBaseUrl = apiBaseUrl.map(String::trim)
                .filter(value -> !value.isBlank())
                .orElse(DEFAULT_API_BASE_URL);
    }

    @Override
    public String type() {
        return "github-release";
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
        String url = apiBaseUrl + "/repos/" + repo;
        int pageSize = cfg.pageSize().orElse(30);
        if (pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException(
                    "The 'github-release' latest source's 'page-size' must be between 1 and 100 "
                            + "(GitHub's per_page cap); was: " + pageSize);
        }
        return new GithubReleaseLatestSource(url, token, pageSize, parser);
    }
}
