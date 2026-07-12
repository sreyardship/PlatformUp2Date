package org.yardship.adapters.out.versionsource.current.http;
import org.yardship.adapters.out.versionsource.current.FailedCurrentSource;
import org.yardship.adapters.out.versionsource.current.CurrentVersionSourceFactory;

import com.fasterxml.jackson.core.JsonPointer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientRequestFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.adapters.out.versionsource.auth.BasicAuthFilter;
import org.yardship.adapters.out.versionsource.auth.BearerAuthFilter;
import org.yardship.adapters.out.versionsource.auth.FileBearerAuthFilter;
import org.yardship.adapters.out.versionsource.current.http.HttpCurrentVersionClient;
import org.yardship.adapters.out.versionsource.current.http.HttpCurrentVersionClientFactory;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.ports.out.CurrentVersionSource;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collection;
import java.util.Optional;

/**
 * Factory for the {@code http} current-version kind. Discovered as a CDI bean; validates its own
 * config fragment ({@code http} requires a non-blank {@code url}, {@code version-key} — if
 * present — must be a syntactically valid JSON Pointer, and {@code ca-cert} together with
 * {@code insecure-skip-tls-verify: true} is refused as ambiguous before any file is resolved),
 * then EAGERLY builds the
 * {@link HttpCurrentVersionClient} via the injected {@link HttpCurrentVersionClientFactory} and constructs a
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

    private final HttpCurrentVersionClientFactory clientFactory;

    @Inject
    public HttpCurrentSourceFactory(HttpCurrentVersionClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    @Override
    public String type() {
        return "http";
    }

    @Override
    public CurrentVersionSource create(ApplicationConfigLoader.VersionSource cfg, VersionParser parser) {
        String url = cfg.url()
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalArgumentException(
                        "The 'http' current source requires a non-blank 'url'."));
        String versionKey = cfg.versionKey().orElse(DEFAULT_VERSION_KEY);
        validatePointerSyntax(versionKey);
        boolean stripPrerelease = cfg.stripPrerelease().orElse(false);
        boolean insecureSkipTlsVerify = cfg.insecureSkipTlsVerify().orElse(false);

        if (insecureSkipTlsVerify && cfg.caCert().isPresent()) {
            String message = "The 'http' current source has both 'ca-cert' and "
                    + "'insecure-skip-tls-verify: true'; this is ambiguous and refused, no "
                    + "precedence rule (url: '" + url + "').";
            logger.warn(message);
            return new FailedCurrentSource(message);
        }

        CaCertResolution caCert = resolveCaCert(cfg.caCert(), url);
        if (caCert.failureMessage().isPresent()) {
            logger.warn(caCert.failureMessage().get());
            return new FailedCurrentSource(caCert.failureMessage().get());
        }
        Optional<KeyStore> trustStore = caCert.trustStore();

        if (insecureSkipTlsVerify) {
            logger.warn("The 'http' current source has 'insecure-skip-tls-verify' enabled; TLS "
                    + "certificate and hostname verification are disabled for url '" + url + "'.");
        }

        if (cfg.auth().isEmpty()) {
            HttpCurrentVersionClient client =
                    clientFactory.build(url, Optional.empty(), trustStore, insecureSkipTlsVerify);
            return new HttpCurrentSource(client, versionKey, stripPrerelease, parser);
        }

        ApplicationConfigLoader.VersionSource.Auth auth = cfg.auth().get();
        Optional<String> failureMessage = validateAuthValue(auth, url);
        if (failureMessage.isPresent()) {
            logger.warn(failureMessage.get());
            return new FailedCurrentSource(failureMessage.get());
        }

        ClientRequestFilter authFilter = buildAuthFilter(auth);
        HttpCurrentVersionClient client =
                clientFactory.build(url, Optional.of(authFilter), trustStore, insecureSkipTlsVerify);
        return new HttpCurrentSource(client, versionKey, stripPrerelease, parser);
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
            boolean hasToken = nonBlank(auth.token()).isPresent();
            boolean hasTokenFile = nonBlank(auth.tokenFile()).isPresent();
            if (hasToken && hasTokenFile) {
                return Optional.of("The 'http' current source's auth.type 'bearer' has both a token "
                        + "and a token-file; this is ambiguous and refused, no precedence rule "
                        + "(url: '" + url + "').");
            }
            if (!hasToken && !hasTokenFile) {
                return Optional.of("The 'http' current source's auth.type 'bearer' needs a token or "
                        + "token-file (url: '" + url + "').");
            }
            return Optional.empty();
        }
        return Optional.of("The 'http' current source's auth.type '" + auth.type()
                + "' is not supported (url: '" + url + "').");
    }

    /**
     * Outcome of resolving the optional {@code ca-cert}: either a value-level failure message (mapped
     * to a {@code FailedCurrentSource} by the caller, never thrown) or the truststore to register on
     * the client ({@link Optional#empty()} when no {@code ca-cert} is configured → JVM default trust).
     */
    private record CaCertResolution(Optional<String> failureMessage, Optional<KeyStore> trustStore) {

        static CaCertResolution failed(String message) {
            return new CaCertResolution(Optional.of(message), Optional.empty());
        }

        static CaCertResolution noTrustStore() {
            return new CaCertResolution(Optional.empty(), Optional.empty());
        }

        static CaCertResolution withTrustStore(KeyStore trustStore) {
            return new CaCertResolution(Optional.empty(), Optional.of(trustStore));
        }
    }

    /**
     * Resolves the optional, transport-level {@code ca-cert} into a per-client truststore. Absent →
     * no custom truststore (JVM default trust). Present-but-blank, or a file that is missing/unreadable/
     * not parseable as X.509/yields zero certs → a value-level failure (WARN + {@code FailedCurrentSource}),
     * NEVER a thrown exception. On success the parsed certs are loaded into a fresh in-memory
     * {@link KeyStore} ({@code load(null, null)}, holding ONLY the supplied CA(s) — it REPLACES, not
     * augments, the JVM bundle for this client only).
     */
    private static CaCertResolution resolveCaCert(Optional<String> caCert, String url) {
        if (caCert.isEmpty()) {
            return CaCertResolution.noTrustStore();
        }
        if (nonBlank(caCert).isEmpty()) {
            return CaCertResolution.failed("The 'http' current source's 'ca-cert' is configured but "
                    + "blank (url: '" + url + "').");
        }
        Path path = Path.of(caCert.get());
        Collection<? extends Certificate> certs;
        try (InputStream in = Files.newInputStream(path)) {
            certs = CertificateFactory.getInstance("X.509").generateCertificates(in);
        } catch (Exception ex) {
            return CaCertResolution.failed("The 'http' current source's 'ca-cert' could not be read as "
                    + "X.509 PEM from '" + path + "' (url: '" + url + "'): " + ex.getMessage());
        }
        if (certs.isEmpty()) {
            return CaCertResolution.failed("The 'http' current source's 'ca-cert' at '" + path
                    + "' contained no X.509 certificates (url: '" + url + "').");
        }
        try {
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            int index = 0;
            for (Certificate cert : certs) {
                trustStore.setCertificateEntry("ca-cert-" + index++, cert);
            }
            return CaCertResolution.withTrustStore(trustStore);
        } catch (Exception ex) {
            return CaCertResolution.failed("The 'http' current source's 'ca-cert' from '" + path
                    + "' could not be loaded into a truststore (url: '" + url + "'): " + ex.getMessage());
        }
    }

    private static ClientRequestFilter buildAuthFilter(ApplicationConfigLoader.VersionSource.Auth auth) {
        if (BEARER_AUTH_TYPE.equals(auth.type())) {
            if (nonBlank(auth.tokenFile()).isPresent()) {
                return new FileBearerAuthFilter(auth.tokenFile().get());
            }
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
