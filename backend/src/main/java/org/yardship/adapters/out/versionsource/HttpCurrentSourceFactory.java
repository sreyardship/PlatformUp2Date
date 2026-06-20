package org.yardship.adapters.out.versionsource;

import com.fasterxml.jackson.core.JsonPointer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientRequestFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yardship.adapters.out.versionclient.ApplicationConfigLoader;
import org.yardship.adapters.out.versionclient.BasicAuthFilter;
import org.yardship.adapters.out.versionclient.BearerAuthFilter;
import org.yardship.adapters.out.versionclient.CurrentVersionClient;
import org.yardship.adapters.out.versionclient.CurrentVersionClientFactory;
import org.yardship.core.ports.out.CurrentVersionSource;

import java.util.Optional;

/**
 * Factory for the {@code http} current-version kind. Discovered as a CDI bean; validates its own
 * config fragment ({@code http} requires a non-blank {@code url}, and {@code version-key} — if
 * present — must be a syntactically valid JSON Pointer), then EAGERLY builds the
 * {@link CurrentVersionClient} via the injected {@link CurrentVersionClientFactory} and constructs a
 * per-app {@link HttpCurrentSource} wrapping it.
 *
 * <p><b>Exfiltration boundary:</b> the {@code current} leg historically only ever hit our own
 * deployment endpoints with no credentials. Since issue 02 (basic) and issue 03 (bearer), this
 * factory CAN send per-app credentials to the app's own configured {@code url} when {@code auth}
 * is present. There is no host check here — the assumption that the credential belongs to the
 * configured {@code url} lives in configuration, not in code (ADR-0008 residual assumption).
 */
@ApplicationScoped
public class HttpCurrentSourceFactory implements CurrentVersionSourceFactory {

    private static final String DEFAULT_VERSION_KEY = "/version";
    private static final String BASIC_AUTH_TYPE = "basic";
    private static final String BEARER_AUTH_TYPE = "bearer";

    private final Logger logger = LoggerFactory.getLogger(HttpCurrentSourceFactory.class);

    private final CurrentVersionClientFactory clientFactory;

    @Inject
    public HttpCurrentSourceFactory(CurrentVersionClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    @Override
    public String type() {
        return "http";
    }

    @Override
    public CurrentVersionSource create(ApplicationConfigLoader.VersionSource cfg) {
        String url = cfg.url()
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalArgumentException(
                        "The 'http' current source requires a non-blank 'url'."));
        String versionKey = cfg.versionKey().orElse(DEFAULT_VERSION_KEY);
        validatePointerSyntax(versionKey);
        boolean stripPrerelease = cfg.stripPrerelease().orElse(false);

        if (cfg.auth().isEmpty()) {
            CurrentVersionClient client = clientFactory.build(url, Optional.empty());
            return new HttpCurrentSource(client, versionKey, stripPrerelease);
        }

        ApplicationConfigLoader.VersionSource.Auth auth = cfg.auth().get();
        Optional<String> failureMessage = validateAuthValue(auth, url);
        if (failureMessage.isPresent()) {
            logger.warn(failureMessage.get());
            return new FailedCurrentSource(failureMessage.get());
        }

        ClientRequestFilter authFilter = buildAuthFilter(auth);
        CurrentVersionClient client = clientFactory.build(url, Optional.of(authFilter));
        return new HttpCurrentSource(client, versionKey, stripPrerelease);
    }

    /**
     * Validates an auth fragment that IS present. Returns a clear failure message when {@code type}
     * is anything other than {@code basic}/{@code bearer}, or the type-specific credentials are
     * missing/blank ({@code basic} needs a username and password; {@code bearer} needs a token);
     * empty when the fragment is valid.
     */
    private static Optional<String> validateAuthValue(
            ApplicationConfigLoader.VersionSource.Auth auth, String url) {
        if (BASIC_AUTH_TYPE.equals(auth.type())) {
            if (nonBlank(auth.username()).isEmpty() || nonBlank(auth.password()).isEmpty()) {
                return Optional.of("The 'http' current source's auth.type 'basic' is missing a "
                        + "username or password (url: '" + url + "').");
            }
            return Optional.empty();
        }
        if (BEARER_AUTH_TYPE.equals(auth.type())) {
            if (nonBlank(auth.token()).isEmpty()) {
                return Optional.of("The 'http' current source's auth.type 'bearer' is missing a "
                        + "token (url: '" + url + "').");
            }
            return Optional.empty();
        }
        return Optional.of("The 'http' current source's auth.type '" + auth.type()
                + "' is not supported (url: '" + url + "').");
    }

    private static ClientRequestFilter buildAuthFilter(ApplicationConfigLoader.VersionSource.Auth auth) {
        if (BEARER_AUTH_TYPE.equals(auth.type())) {
            return new BearerAuthFilter(auth.token().get());
        }
        return new BasicAuthFilter(auth.username().get(), auth.password().get());
    }

    private static Optional<String> nonBlank(Optional<String> value) {
        return value.filter(v -> !v.isBlank());
    }

    private static void validatePointerSyntax(String versionKey) {
        try {
            JsonPointer.compile(versionKey);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "The 'http' current source's 'version-key' is not a valid JSON Pointer: '"
                            + versionKey + "'.", ex);
        }
    }
}
