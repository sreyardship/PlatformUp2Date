package org.yardship.adapters.out.versionsource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.yardship.adapters.out.versionclient.ApplicationConfigLoader;
import org.yardship.core.ports.out.LatestVersionSource;

import java.util.Optional;

/**
 * Factory for the {@code github-release} latest-version kind. Discovered as a CDI bean; validates
 * its own config fragment ({@code github-release} requires a non-blank {@code url}) and constructs a
 * per-app {@link GithubReleaseLatestSource}.
 *
 * <p>This factory OWNS the GitHub token concern: it holds the configured token (sourced from
 * {@link ApplicationConfigLoader#github()} on the CDI path) and hands it to every source it builds,
 * so the auth decision never leaks to the resolver or the core.
 */
@ApplicationScoped
public class GithubReleaseLatestSourceFactory implements LatestVersionSourceFactory {

    private final Optional<String> token;

    @Inject
    public GithubReleaseLatestSourceFactory(ApplicationConfigLoader configLoader) {
        this(configLoader.github().token());
    }

    // Visible for testing: lets tests build the factory without a CDI container / configured token.
    public GithubReleaseLatestSourceFactory(Optional<String> token) {
        this.token = token;
    }

    @Override
    public String type() {
        return "github-release";
    }

    @Override
    public LatestVersionSource create(ApplicationConfigLoader.VersionSource cfg) {
        String url = cfg.url()
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalArgumentException(
                        "The 'github-release' latest source requires a non-blank 'url'."));
        int pageSize = cfg.pageSize().orElse(30);
        if (pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException(
                    "The 'github-release' latest source's 'page-size' must be between 1 and 100 "
                            + "(GitHub's per_page cap); was: " + pageSize);
        }
        return new GithubReleaseLatestSource(url, token, pageSize);
    }
}
