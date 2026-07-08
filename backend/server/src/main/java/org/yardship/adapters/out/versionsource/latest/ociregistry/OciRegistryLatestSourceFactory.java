package org.yardship.adapters.out.versionsource.latest.ociregistry;

import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.adapters.out.versionsource.latest.FailedLatestSource;
import org.yardship.adapters.out.versionsource.latest.LatestVersionSourceFactory;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.ports.out.LatestVersionSource;

import java.util.Optional;

/**
 * Factory for the {@code oci-registry} latest-version kind. Discovered as a CDI bean; validates
 * its own config fragment ({@code oci-registry} requires a non-blank {@code registry} and a
 * non-blank {@code repo}) and constructs a per-app {@link OciRegistryLatestSource}.
 *
 * <p>This factory OWNS the URL-assembly concern: it builds {@code https://{registry}/v2/{repo}}
 * by default, honouring an explicit {@code http://} prefix on the {@code registry} value for
 * local/test registries.
 *
 * <p>Structural validation (missing/blank {@code registry} or {@code repo}) throws
 * {@link IllegalArgumentException} with a message naming the offending field — surfaces as a
 * boot-time failure, not a per-app scrape error.
 *
 * <p>VALUE-LEVEL auth validation ({@code auth.type} other than {@code basic}, or {@code basic}
 * with missing/blank credentials) returns a {@link FailedLatestSource} instead of throwing, so the
 * offending app fails on every {@link LatestVersionSource#version()} call while every other app
 * keeps scraping normally — mirroring {@code HttpCurrentSourceFactory.validateAuthValue}.
 * The supported auth type is {@code basic} only; {@code bearer} and {@code token-file} send a
 * static token and do not fit the realm-mint flow (ADR-0013).
 */
@ApplicationScoped
public class OciRegistryLatestSourceFactory implements LatestVersionSourceFactory {

    private static final String BASIC_AUTH_TYPE = "basic";

    private final Logger logger = LoggerFactory.getLogger(OciRegistryLatestSourceFactory.class);

    @Override
    public String type() {
        return "oci-registry";
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
        TagSelection selection = tagSelectionFrom(cfg);

        if (cfg.auth().isEmpty()) {
            return new OciRegistryLatestSource(baseUrl, Optional.empty(), Optional.empty(), selection, parser);
        }

        ApplicationConfigLoader.VersionSource.Auth auth = cfg.auth().get();
        Optional<String> failureMessage = validateAuthValue(auth, registry);
        if (failureMessage.isPresent()) {
            logger.warn(failureMessage.get());
            return new FailedLatestSource(failureMessage.get());
        }

        String username = nonBlank(auth.username()).get();
        String password = nonBlank(auth.password()).get();
        return new OciRegistryLatestSource(
                baseUrl, Optional.of(username), Optional.of(password), selection, parser);
    }

    /**
     * Validates an auth fragment that IS present. Only {@code basic} is supported for
     * {@code oci-registry} (ADR-0013): {@code bearer} and {@code token-file} send a static token
     * at the resource and do not fit the realm-mint flow. Returns a clear failure message when the
     * type is unsupported or the {@code basic} credentials are missing/blank; empty on success.
     */
    private static Optional<String> validateAuthValue(
            ApplicationConfigLoader.VersionSource.Auth auth, String registry) {
        if (!BASIC_AUTH_TYPE.equals(auth.type())) {
            return Optional.of("The 'oci-registry' latest source's auth.type '" + auth.type()
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
     * {@code prerelease-filter} is passed through, and {@code strip-prerelease} defaults to
     * {@code false} (see ADR-0014). Visible for testing so the defaulting/threading of each leaf is
     * asserted directly, rather than only that a non-null source is built.
     */
    public static TagSelection tagSelectionFrom(ApplicationConfigLoader.VersionSource cfg) {
        return new TagSelection(
                cfg.pageSize().orElse(100),
                cfg.maxTags().orElse(1000),
                cfg.prereleaseFilter(),
                cfg.stripPrerelease().orElse(false));
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
