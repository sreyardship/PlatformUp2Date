package org.yardship.adapters.out.versionsource.current.httpjson;

import com.fasterxml.jackson.core.JsonPointer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.adapters.out.versionsource.current.CurrentVersionSourceFactory;
import org.yardship.adapters.out.versionsource.current.FailedCurrentSource;
import org.yardship.adapters.out.versionsource.http.HttpTransportConfig;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.ports.out.CurrentVersionSource;

/**
 * Factory for the {@code http-json} current-version kind. Discovered as a CDI bean; validates its own
 * config fragment ({@code http-json} requires a non-blank {@code url}, {@code version-key} — if
 * present — must be a syntactically valid JSON Pointer), delegates the {@code auth} / {@code ca-cert}
 * / {@code insecure-skip-tls-verify} part of the fragment to the shared {@link HttpTransportConfig}
 * collaborator, then EAGERLY builds the {@link HttpJsonCurrentVersionClient} via the injected
 * {@link HttpJsonCurrentVersionClientFactory} and constructs a per-app {@link HttpJsonCurrentSource} wrapping
 * it.
 *
 * <p><b>Exfiltration boundary:</b> this factory sends per-app credentials to the configured
 * {@code url} only when {@code auth} is present. There is no host check on the CONFIGURED {@code url} itself — the assumption that
 * the credential belongs to it lives in configuration, not in code (ADR-0008 residual assumption).
 * If that {@code url} responds with a 301/302/303/307/308, the credential's fate on the redirected
 * request is a SEPARATE, explicitly-decided concern: ADR-0029 (see
 * {@code docs/adr/0029-authorization-does-not-cross-redirect-origins.md}) governs it, not this
 * factory — the {@link HttpJsonCurrentVersionClientFactory}-built client retains the rendered
 * {@code Authorization} header only when the redirect target is same-origin (scheme, host, and
 * effective port unchanged), stripping it before contacting any cross-origin target.
 */
@ApplicationScoped
public class HttpJsonCurrentSourceFactory implements CurrentVersionSourceFactory {

    private static final String DEFAULT_VERSION_KEY = "/version";
    private static final String KIND_LABEL = "http-json";

    private final HttpJsonCurrentVersionClientFactory clientFactory;
    private final HttpTransportConfig transportConfig = new HttpTransportConfig(KIND_LABEL);

    @Inject
    public HttpJsonCurrentSourceFactory(HttpJsonCurrentVersionClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

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
        boolean insecureSkipTlsVerify = cfg.insecureSkipTlsVerify().orElse(false);

        HttpTransportConfig.Resolution resolution =
                transportConfig.resolve(cfg.auth(), cfg.caCert(), insecureSkipTlsVerify, url);
        if (resolution.failureMessage().isPresent()) {
            return new FailedCurrentSource(resolution.failureMessage().get());
        }

        HttpJsonCurrentVersionClient client = clientFactory.build(
                url, resolution.authFilter(), resolution.trustStore(), resolution.insecureSkipTlsVerify());
        return new HttpJsonCurrentSource(client, versionKey, stripPrerelease, parser);
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
