package org.yardship.adapters.out.versionsource.current.httpheader;

import jakarta.enterprise.context.ApplicationScoped;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.adapters.out.versionsource.current.CurrentVersionSourceFactory;
import org.yardship.adapters.out.versionsource.current.FailedCurrentSource;
import org.yardship.adapters.out.versionsource.current.HttpCurrentSource;
import org.yardship.adapters.out.versionsource.http.HttpTransport;
import org.yardship.adapters.out.versionsource.http.HttpTransportConfig;
import org.yardship.adapters.out.versionsource.regex.RegexVersionExtractor;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.ports.out.CurrentVersionSource;

import java.util.Optional;

/**
 * Factory for the {@code http-header} current-version kind (ADR-0030). Discovered as a CDI bean;
 * registered by mere existence — no central dispatcher is edited (ADR-0005). Validates its own
 * STRUCTURAL config fragment fail-fast in {@link #create}: a non-blank {@code url} and a non-blank
 * {@code version-header} are required, as {@code url} is for the {@code http-json} kind; a configured
 * {@code regex} must compile and have at least one capture group, validated by
 * {@link RegexVersionExtractor}'s constructor. All three THROW an {@link IllegalArgumentException},
 * the declared "this config fragment is unusable" signal {@code VersionSourceResolver} records as
 * a per-app {@code ConfigError} (ADR-0032).
 *
 * <p>A blank {@code regex} (e.g. {@code "   "}) is treated the same as an absent one — silently
 * optional — matching how optional fields are read across the codebase:
 * {@code HttpRegexLatestSourceFactory}'s {@code nonBlank}, {@code GithubReleaseLatestSourceFactory}'s
 * optional token, and {@code OciRegistryLatestSourceFactory} all filter a blank value to absent.
 * Only {@code url} and {@code version-header} are required and reject a blank value. Note this is
 * NOT how a blank {@code ca-cert} behaves: that is a VALUE error yielding a
 * {@link FailedCurrentSource}, resolved by {@link HttpTransportConfig} below.
 *
 * <p>The {@code auth} / {@code ca-cert} / {@code insecure-skip-tls-verify} part of the fragment is
 * delegated to the shared, kind-labelled {@link HttpTransportConfig}, constructed here with the
 * {@code "http-header"} label so its messages name this kind. A VALUE-level problem there is mapped
 * to a {@link FailedCurrentSource} with a WARN, never a thrown exception — the single app degrades,
 * the fleet keeps scraping. On success the factory builds the shared {@link HttpTransport} and
 * pairs it with this kind's {@link HttpHeaderVersionExtractor}.
 */
@ApplicationScoped
public class HttpHeaderCurrentSourceFactory implements CurrentVersionSourceFactory {

    private static final String KIND_LABEL = "http-header";
    private static final String EXTRACTOR_LABEL = "'http-header' current source";

    private final HttpTransportConfig transportConfig = HttpTransportConfig.forCurrentSource(KIND_LABEL);

    @Override
    public String type() {
        return KIND_LABEL;
    }

    @Override
    public CurrentVersionSource create(ApplicationConfigLoader.VersionSource cfg, VersionParser parser) {
        String url = requireNonBlank(cfg.url(), "url");
        String versionHeader = requireNonBlank(cfg.versionHeader(), "version-header");
        // A blank `regex` is treated as absent (see class Javadoc), consistent with how this
        // factory treats every other optional field.
        Optional<RegexVersionExtractor> regex = cfg.regex()
                .filter(value -> !value.isBlank())
                .map(pattern -> new RegexVersionExtractor(EXTRACTOR_LABEL, pattern, parser));
        boolean stripPrerelease = cfg.stripPrerelease().orElse(false);

        HttpTransportConfig.Resolution resolution =
                transportConfig.resolve(cfg, url);
        if (resolution.failureMessage().isPresent()) {
            return new FailedCurrentSource(resolution.failureMessage().get());
        }

        return new HttpCurrentSource(
                HttpTransport.from(url, resolution),
                new HttpHeaderVersionExtractor(url, versionHeader, regex, stripPrerelease, parser));
    }

    private static String requireNonBlank(Optional<String> value, String fieldName) {
        return value.filter(v -> !v.isBlank())
                .orElseThrow(() -> new IllegalArgumentException(
                        "The 'http-header' current source requires a non-blank '" + fieldName + "'."));
    }
}
