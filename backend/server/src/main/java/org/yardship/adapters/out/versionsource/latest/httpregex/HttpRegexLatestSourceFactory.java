package org.yardship.adapters.out.versionsource.latest.httpregex;

import jakarta.enterprise.context.ApplicationScoped;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.adapters.out.versionsource.latest.LatestVersionSourceFactory;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.ports.out.LatestVersionSource;

import java.util.Optional;

/**
 * Factory for the {@code http-regex} latest-version kind. Discovered as a CDI bean; holds no external
 * dependencies. Validates its own config fragment fail-fast in {@link #create}: a non-blank
 * {@code url} and a non-blank {@code regex}. Whether that {@code regex} compiles and has at least
 * one capture group (the source extracts group 1) is validated by
 * {@link org.yardship.adapters.out.versionsource.regex.RegexVersionExtractor}, the leg-neutral
 * shared machinery constructed inside {@link HttpRegexLatestSource}'s constructor when this factory
 * builds the source below — that constructor call is what actually fails boot on a bad pattern.
 * These are STRUCTURAL config errors, so they fail boot — consistent with {@code github-release}'s
 * treatment of a missing/malformed {@code repo}.
 */
@ApplicationScoped
public class HttpRegexLatestSourceFactory implements LatestVersionSourceFactory {

    @Override
    public String type() {
        return "http-regex";
    }

    @Override
    public LatestVersionSource create(ApplicationConfigLoader.VersionSource cfg, VersionParser parser) {
        String url = nonBlank(cfg.url())
                .orElseThrow(() -> new IllegalArgumentException(
                        "The 'http-regex' latest source requires a non-blank 'url'."));
        String regex = nonBlank(cfg.regex())
                .orElseThrow(() -> new IllegalArgumentException(
                        "The 'http-regex' latest source requires a non-blank 'regex'."));

        return new HttpRegexLatestSource(url, regex, parser);
    }

    private static Optional<String> nonBlank(Optional<String> value) {
        return value.filter(v -> !v.isBlank());
    }
}
