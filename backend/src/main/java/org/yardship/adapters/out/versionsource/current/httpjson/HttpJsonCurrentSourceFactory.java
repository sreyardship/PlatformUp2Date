package org.yardship.adapters.out.versionsource.current.httpjson;

import com.fasterxml.jackson.core.JsonPointer;
import jakarta.enterprise.context.ApplicationScoped;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.adapters.out.versionsource.current.CurrentVersionSourceFactory;
import org.yardship.adapters.out.versionsource.current.FailedCurrentSource;
import org.yardship.adapters.out.versionsource.current.HttpCurrentSource;
import org.yardship.adapters.out.versionsource.http.HttpTransport;
import org.yardship.adapters.out.versionsource.http.HttpTransportConfig;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.ports.out.CurrentVersionSource;

/**
 * Factory for the {@code http-json} current-version kind. Discovered as a CDI bean; registered by
 * mere existence — no central dispatcher is edited (ADR-0005). Validates its own STRUCTURAL config
 * fragment fail-fast in {@link #create}: a non-blank {@code url} is required, and
 * {@code version-key} — if present — must be a syntactically valid JSON Pointer. Both THROW an
 * {@link IllegalArgumentException}, the declared "this config fragment is unusable" signal
 * {@code VersionSourceResolver} records as a per-app {@code ConfigError} (ADR-0032).
 *
 * <p>The {@code auth} / {@code ca-cert} / {@code insecure-skip-tls-verify} part of the fragment is
 * delegated to the shared, kind-labelled {@link HttpTransportConfig}; a VALUE-level problem there
 * is mapped to a {@link FailedCurrentSource} with a WARN, never a thrown exception — the single app
 * degrades, the fleet keeps scraping. On success the factory builds the shared
 * {@link HttpTransport} and pairs it with this kind's {@link HttpJsonVersionExtractor}.
 *
 * <p><b>Exfiltration boundary:</b> per-app credentials are sent to the configured {@code url} only
 * when {@code auth} is present. There is no host check on the configured {@code url} itself — the
 * assumption that the credential belongs to it lives in configuration, not in code (ADR-0008
 * residual assumption). What happens to the credential across a redirect is the transport's
 * concern, governed by ADR-0029.
 */
@ApplicationScoped
public class HttpJsonCurrentSourceFactory implements CurrentVersionSourceFactory {

    private static final String DEFAULT_VERSION_KEY = "/version";
    private static final String KIND_LABEL = "http-json";

    private final HttpTransportConfig transportConfig = HttpTransportConfig.forCurrentSource(KIND_LABEL);

    @Override
    public String type() {
        return KIND_LABEL;
    }

    @Override
    public CurrentVersionSource create(ApplicationConfigLoader.VersionSource cfg, VersionParser parser) {
        String url = cfg.url()
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalArgumentException(
                        "The 'http-json' current source requires a non-blank 'url'."));
        String versionKey = cfg.versionKey().orElse(DEFAULT_VERSION_KEY);
        validatePointerSyntax(versionKey);
        boolean stripPrerelease = cfg.stripPrerelease().orElse(false);

        HttpTransportConfig.Resolution resolution = transportConfig.resolve(cfg, url);
        if (resolution.failureMessage().isPresent()) {
            return new FailedCurrentSource(resolution.failureMessage().get());
        }

        return new HttpCurrentSource(
                HttpTransport.from(url, resolution),
                new HttpJsonVersionExtractor(versionKey, stripPrerelease, parser));
    }

    private static void validatePointerSyntax(String versionKey) {
        try {
            JsonPointer.compile(versionKey);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "The 'http-json' current source's 'version-key' is not a valid JSON Pointer: '"
                            + versionKey + "'.", ex);
        }
    }
}
