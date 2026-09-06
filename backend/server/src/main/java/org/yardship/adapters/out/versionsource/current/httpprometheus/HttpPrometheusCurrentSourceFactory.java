package org.yardship.adapters.out.versionsource.current.httpprometheus;

import jakarta.enterprise.context.ApplicationScoped;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.adapters.out.versionsource.current.CurrentVersionSourceFactory;
import org.yardship.adapters.out.versionsource.current.FailedCurrentSource;
import org.yardship.adapters.out.versionsource.http.HttpTransportConfig;
import org.yardship.adapters.out.versionsource.http.RedirectFollowingHttpGet;
import org.yardship.adapters.out.versionsource.regex.RegexVersionExtractor;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.ports.out.CurrentVersionSource;

import java.net.URI;
import java.util.Optional;

/**
 * Factory for the {@code http-prometheus} current-version kind (ADR-0033). Discovered as a CDI
 * bean; registered by mere existence — no central dispatcher is edited (ADR-0005). Validates its
 * own STRUCTURAL config fragment fail-fast in {@link #create}: a non-blank {@code url} and a
 * non-blank {@code metric} are required. Both THROW an {@link IllegalArgumentException} — the
 * declared "this config fragment is unusable" signal {@code VersionSourceResolver} catches and
 * records as a per-app {@code ConfigError} at WARN, never a boot failure (ADR-0032).
 *
 * <p>{@code version-label} defaults FACTORY-SIDE to {@code "version"} — exactly as
 * {@code HttpJsonCurrentSourceFactory} defaults {@code version-key} to {@code /version} — not via
 * {@code @WithDefault}.
 *
 * <p>A configured {@code regex} (ADR-0033, issue 03) must compile and have at least one capture
 * group, validated by {@link RegexVersionExtractor}'s constructor, built here with the
 * {@code "'http-prometheus' current source"} label so its messages name this kind. That THROWS an
 * {@link IllegalArgumentException} — the same "this config fragment is unusable" signal as the
 * {@code url} / {@code metric} checks above, degrading only this app's {@code current} side
 * (ADR-0032), never the boot. A blank {@code regex} (e.g. {@code "   "}) is treated the same as an
 * absent one — silently optional — matching {@code HttpHeaderCurrentSourceFactory}'s identical
 * treatment. {@code strip-prerelease} defaults to {@code false}.
 *
 * <p>The {@code auth} / {@code ca-cert} / {@code insecure-skip-tls-verify} part of the fragment is
 * delegated to the shared, kind-labelled {@link HttpTransportConfig} collaborator, constructed
 * here with the {@code "http-prometheus"} label so its messages name this kind. A VALUE-level
 * problem there (e.g. an unsupported {@code auth.type}, or a {@code ca-cert} file that cannot be
 * read) is mapped to a {@link FailedCurrentSource} with a WARN, never a thrown exception — the
 * single app degrades, the fleet keeps scraping.
 *
 * <p>No CDI-injected collaborators: like the {@code http-header} kind, this kind's transport is a
 * plain {@link RedirectFollowingHttpGet}, built directly from the resolved TLS inputs.
 */
@ApplicationScoped
public class HttpPrometheusCurrentSourceFactory implements CurrentVersionSourceFactory {

    private static final String KIND_LABEL = "http-prometheus";
    private static final String DEFAULT_VERSION_LABEL = "version";
    private static final String EXTRACTOR_LABEL = "'http-prometheus' current source";

    private final HttpTransportConfig transportConfig = new HttpTransportConfig(KIND_LABEL);

    @Override
    public String type() {
        return KIND_LABEL;
    }

    @Override
    public CurrentVersionSource create(ApplicationConfigLoader.VersionSource cfg, VersionParser parser) {
        String url = requireNonBlank(cfg.url(), "url");
        String metric = requireNonBlank(cfg.metric(), "metric");
        String versionLabel = cfg.versionLabel().filter(value -> !value.isBlank()).orElse(DEFAULT_VERSION_LABEL);
        // A blank `regex` is treated as absent (see class Javadoc), consistent with how this
        // factory treats every other optional field.
        Optional<RegexVersionExtractor> extractor = cfg.regex()
                .filter(value -> !value.isBlank())
                .map(regex -> new RegexVersionExtractor(EXTRACTOR_LABEL, regex, parser));
        boolean stripPrerelease = cfg.stripPrerelease().orElse(false);
        boolean insecureSkipTlsVerify = cfg.insecureSkipTlsVerify().orElse(false);

        HttpTransportConfig.Resolution resolution =
                transportConfig.resolve(cfg.auth(), cfg.caCert(), insecureSkipTlsVerify, url);
        if (resolution.failureMessage().isPresent()) {
            return new FailedCurrentSource(resolution.failureMessage().get());
        }

        RedirectFollowingHttpGet http =
                RedirectFollowingHttpGet.withTls(resolution.trustStore(), resolution.insecureSkipTlsVerify());
        PrometheusBodyFetch fetch =
                new RedirectFollowingPrometheusBodyFetch(http, URI.create(url), resolution.authFilter());
        return new HttpPrometheusCurrentSource(
                fetch, url, metric, versionLabel, cfg.labels(), extractor, stripPrerelease, parser);
    }

    private static String requireNonBlank(Optional<String> value, String fieldName) {
        return value.filter(v -> !v.isBlank())
                .orElseThrow(() -> new IllegalArgumentException(
                        "The 'http-prometheus' current source requires a non-blank '" + fieldName + "'."));
    }
}
