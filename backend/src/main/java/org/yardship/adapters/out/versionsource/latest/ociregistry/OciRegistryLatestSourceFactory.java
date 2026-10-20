package org.yardship.adapters.out.versionsource.latest.ociregistry;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.client.ClientRequestFilter;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.adapters.out.versionsource.auth.BasicAuthFilter;
import org.yardship.adapters.out.versionsource.http.HttpTransportConfig;
import org.yardship.adapters.out.versionsource.latest.FailedLatestSource;
import org.yardship.adapters.out.versionsource.latest.LatestVersionSourceFactory;
import org.yardship.adapters.out.versionsource.regex.RegexVersionExtractor;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.ports.out.LatestVersionSource;

import java.util.Optional;

/**
 * Factory for the {@code oci-registry} latest-version kind. Discovered as a CDI bean; validates
 * its own STRUCTURAL config fragment ({@code oci-registry} requires a non-blank {@code registry} and
 * a non-blank {@code repo}; a missing/blank value THROWS an {@link IllegalArgumentException} naming
 * the field, the declared "this config fragment is unusable" signal the resolver records as a
 * per-app {@code ConfigError} per ADR-0032) and constructs a per-app {@link OciRegistryLatestSource}.
 *
 * <p>This factory OWNS the URL-assembly concern: it builds {@code https://{registry}/v2/{repo}}
 * by default, honouring an explicit {@code http://} prefix on the {@code registry} value for
 * local/test registries.
 *
 * <p>The {@code ca-cert} / {@code insecure-skip-tls-verify} part of the fragment is delegated to
 * the shared, kind-labelled {@link HttpTransportConfig} (ADR-0040) — a private Harbor behind a
 * company CA is configured like any {@code http-json} current source. {@code auth} is NOT: only
 * {@code basic} fits the realm-mint flow of ADR-0013 ({@code bearer} and {@code token-file} would
 * send a static token at the resource), so this factory validates it itself and hands the
 * credential to the source for the token realm, never as the transport's configured credential.
 * Either VALUE-level problem returns a {@link FailedLatestSource} rather than throwing, so the
 * offending app fails on every {@link LatestVersionSource#version()} while every other app keeps
 * scraping normally.
 *
 * <p>An optional {@code regex} (ADR-0042) is compiled first, before any value-level check, so a
 * pattern that does not compile or has no capture group always surfaces as the per-app config error
 * (an {@link IllegalArgumentException}, ADR-0032). A blank {@code regex} is treated as absent.
 */
@ApplicationScoped
public class OciRegistryLatestSourceFactory implements LatestVersionSourceFactory {

    private static final String KIND_LABEL = "oci-registry";
    private static final String BASIC_AUTH_TYPE = "basic";
    private static final String EXTRACTOR_LABEL = "'oci-registry' latest source";

    private final HttpTransportConfig transportConfig = HttpTransportConfig.forLatestSource(KIND_LABEL);

    @Override
    public String type() {
        return KIND_LABEL;
    }

    @Override
    public LatestVersionSource create(ApplicationConfigLoader.VersionSource cfg, VersionParser parser) {
        String registry = cfg.registry()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalArgumentException(
                        "The 'oci-registry' latest source requires a non-blank 'registry'."));
        String repo = cfg.repo()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalArgumentException(
                        "The 'oci-registry' latest source requires a non-blank 'repo'."));
        String baseUrl = assembleBaseUrl(registry, repo);
        TagSelection selection = tagSelectionFrom(cfg, parser);

        // auth is resolved below under this kind's own basic-only rule, so none is passed here.
        HttpTransportConfig.Resolution tlsResolution = transportConfig.resolve(
                Optional.empty(), cfg.caCert(), cfg.insecureSkipTlsVerify().orElse(false), baseUrl + "/tags/list");
        if (tlsResolution.failureMessage().isPresent()) {
            return new FailedLatestSource(tlsResolution.failureMessage().get());
        }

        if (cfg.auth().isEmpty()) {
            return new OciRegistryLatestSource(baseUrl, tlsResolution, Optional.empty(), selection, parser);
        }
        ApplicationConfigLoader.VersionSource.Auth auth = cfg.auth().get();
        Optional<String> failureMessage = validateAuthValue(auth, registry);
        if (failureMessage.isPresent()) {
            return new FailedLatestSource(failureMessage.get());
        }
        Optional<ClientRequestFilter> realmCredential =
                Optional.of(new BasicAuthFilter(nonBlank(auth.username()).get(), nonBlank(auth.password()).get()));
        return new OciRegistryLatestSource(baseUrl, tlsResolution, realmCredential, selection, parser);
    }

    /**
     * Validates an auth fragment that IS present. Only {@code basic} is supported for
     * {@code oci-registry} (ADR-0013): {@code bearer} and {@code token-file} send a static token
     * at the resource and do not fit the realm-mint flow. Returns a clear failure message when the
     * type is unsupported or the {@code basic} credentials are missing/blank; empty on success.
     */
    private static Optional<String> validateAuthValue(
            ApplicationConfigLoader.VersionSource.Auth auth, String registry) {
        if (auth.type().isEmpty()) {
            return Optional.of("The 'oci-registry' latest source has an 'auth' block with no "
                    + "'type' configured (registry: '" + registry + "').");
        }
        if (!BASIC_AUTH_TYPE.equals(auth.type().get())) {
            return Optional.of("The 'oci-registry' latest source's auth.type '" + auth.type().get()
                    + "' is not supported (registry: '" + registry + "'). "
                    + "Only 'basic' is supported; 'bearer' and 'token-file' do not fit the "
                    + "realm-mint flow (see ADR-0013).");
        }
        if (nonBlank(auth.username()).isEmpty() || nonBlank(auth.password()).isEmpty()) {
            return Optional.of("The 'oci-registry' latest source's auth.type 'basic' is missing "
                    + "a username or password (registry: '" + registry + "').");
        }
        return Optional.empty();
    }

    /**
     * Maps a config fragment onto the {@link TagSelection} the source uses: {@code page-size}
     * defaults to 100 (the {@code n} query parameter), {@code max-tags} to 1000 (the safety cap),
     * {@code prerelease-filter} is passed through, {@code strip-prerelease} defaults to
     * {@code false} (see ADR-0014), and a non-blank {@code regex} is compiled under the app's
     * parser (ADR-0042), throwing {@link IllegalArgumentException} when it is unusable. Visible for testing so the defaulting/threading of each leaf is
     * asserted directly, rather than only that a non-null source is built.
     */
    public static TagSelection tagSelectionFrom(ApplicationConfigLoader.VersionSource cfg, VersionParser parser) {
        return new TagSelection(
                cfg.pageSize().orElse(100),
                cfg.maxTags().orElse(1000),
                cfg.prereleaseFilter(),
                cfg.stripPrerelease().orElse(false),
                RegexVersionExtractor.optional(EXTRACTOR_LABEL, cfg.regex(), parser));
    }

    private static String assembleBaseUrl(String registry, String repo) {
        String registryWithScheme;
        if (registry.startsWith("http://") || registry.startsWith("https://")) {
            registryWithScheme = registry;
        } else {
            registryWithScheme = "https://" + registry;
        }
        return registryWithScheme + "/v2/" + repo;
    }

    private static Optional<String> nonBlank(Optional<String> value) {
        return value.filter(v -> !v.isBlank());
    }
}
