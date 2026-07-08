package org.yardship.adapters.out.versionsource.latest.httpregex;

import jakarta.enterprise.context.ApplicationScoped;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.adapters.out.versionsource.latest.LatestVersionSourceFactory;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.ports.out.LatestVersionSource;

import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Factory for the {@code http-regex} latest-version kind. Discovered as a CDI bean; holds no external
 * dependencies. Validates its own config fragment fail-fast in {@link #create}: a non-blank
 * {@code url} and a {@code regex} that is present, compiles, and has at least one capture group (the
 * source extracts group 1). These are STRUCTURAL config errors, so they fail boot — consistent with
 * {@code github-release}'s treatment of a missing/malformed {@code repo}.
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

        Pattern pattern;
        try {
            pattern = Pattern.compile(regex);
        } catch (PatternSyntaxException ex) {
            throw new IllegalArgumentException(
                    "The 'http-regex' latest source's 'regex' does not compile: " + ex.getMessage(), ex);
        }
        if (pattern.matcher("").groupCount() < 1) {
            throw new IllegalArgumentException(
                    "The 'http-regex' latest source's 'regex' must have at least one capture group "
                            + "(the source reads group 1); was: '" + regex + "'.");
        }

        return new HttpRegexLatestSource(url, regex, parser);
    }

    private static Optional<String> nonBlank(Optional<String> value) {
        return value.filter(v -> !v.isBlank());
    }
}
