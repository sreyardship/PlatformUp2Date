package org.yardship.adapters.out.versionsource.latest.httpregex;

import jakarta.enterprise.context.ApplicationScoped;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.adapters.out.versionsource.http.HttpTransport;
import org.yardship.adapters.out.versionsource.http.HttpTransportConfig;
import org.yardship.adapters.out.versionsource.latest.FailedLatestSource;
import org.yardship.adapters.out.versionsource.latest.HttpLatestSource;
import org.yardship.adapters.out.versionsource.latest.LatestVersionSourceFactory;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.ports.out.LatestVersionSource;

import java.util.Optional;

/**
 * Factory for the {@code http-regex} latest-version kind. Discovered as a CDI bean; validates its
 * own STRUCTURAL config fragment fail-fast in {@link #create}: a non-blank {@code url} and a
 * non-blank {@code regex} that compiles with at least one capture group (the kind reads group 1).
 * Each THROWS an {@link IllegalArgumentException} — the regex ones from
 * {@link HttpRegexVersionExtractor}'s constructor — the declared "this config fragment is unusable"
 * signal {@code VersionSourceResolver} records as a per-app {@code ConfigError} (ADR-0032).
 *
 * <p>The {@code auth} / {@code ca-cert} / {@code insecure-skip-tls-verify} part of the fragment is
 * delegated to the shared, kind-labelled {@link HttpTransportConfig}; a VALUE-level problem there
 * is mapped to a {@link FailedLatestSource}, never a thrown exception — the single app degrades,
 * the fleet keeps scraping. On success the factory builds the shared {@link HttpTransport} and
 * pairs it with this kind's {@link HttpRegexVersionExtractor} (ADR-0039, ADR-0040). A vendor
 * listing behind a private CA or behind basic auth is therefore configured exactly like an
 * {@code http-json} current source.
 *
 * <p><b>Exfiltration boundary:</b> per-app credentials are sent to the configured {@code url}
 * only when {@code auth} is present, and never across a redirect onto another origin (ADR-0029).
 */
@ApplicationScoped
public class HttpRegexLatestSourceFactory implements LatestVersionSourceFactory {

    private static final String KIND_LABEL = "http-regex";

    private final HttpTransportConfig transportConfig = HttpTransportConfig.forLatestSource(KIND_LABEL);

    @Override
    public String type() {
        return KIND_LABEL;
    }

    @Override
    public LatestVersionSource create(ApplicationConfigLoader.VersionSource cfg, VersionParser parser) {
        String url = nonBlank(cfg.url())
                .orElseThrow(() -> new IllegalArgumentException(
                        "The 'http-regex' latest source requires a non-blank 'url'."));
        String regex = nonBlank(cfg.regex())
                .orElseThrow(() -> new IllegalArgumentException(
                        "The 'http-regex' latest source requires a non-blank 'regex'."));
        HttpRegexVersionExtractor extractor = new HttpRegexVersionExtractor(regex, parser);

        HttpTransportConfig.Resolution resolution = transportConfig.resolve(cfg, url);
        if (resolution.failureMessage().isPresent()) {
            return new FailedLatestSource(resolution.failureMessage().get());
        }

        return new HttpLatestSource(HttpTransport.from(url, resolution), extractor);
    }

    private static Optional<String> nonBlank(Optional<String> value) {
        return value.filter(v -> !v.isBlank());
    }
}
