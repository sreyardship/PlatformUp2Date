package org.yardship.adapters.out.versionsource.current.http;

import jakarta.ws.rs.client.ClientRequestFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.adapters.out.versionsource.auth.BasicAuthFilter;
import org.yardship.adapters.out.versionsource.auth.BearerAuthFilter;
import org.yardship.adapters.out.versionsource.auth.FileBearerAuthFilter;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collection;
import java.util.Optional;

/**
 * Stateless, kind-labelled collaborator shared by every current-leg HTTP factory (e.g. {@code http},
 * {@code http-header}). Resolves the {@code auth} / {@code ca-cert} / {@code insecure-skip-tls-verify}
 * part of a config fragment into either a value-level failure message (never a thrown exception) or
 * the resolved transport inputs: an optional {@link ClientRequestFilter}, an optional truststore, and
 * the insecure-TLS flag.
 *
 * <p>The {@code kindLabel} supplied at construction is substituted into every message so wording reads
 * {@code "The 'http' current source's ..."} for the {@code http} kind and {@code "The 'http-header'
 * current source's ..."} for {@code http-header}, etc. This collaborator introduces no new lifecycle —
 * every current-leg HTTP factory constructs or injects it directly.
 */
public class HttpCurrentTransportConfig {

    private static final String BASIC_AUTH_TYPE = "basic";
    private static final String BEARER_AUTH_TYPE = "bearer";

    private final Logger logger = LoggerFactory.getLogger(HttpCurrentTransportConfig.class);

    private final String kindLabel;

    public HttpCurrentTransportConfig(String kindLabel) {
        this.kindLabel = kindLabel;
    }

    /**
     * Outcome of {@link #resolve}: either a value-level failure message (mapped to a
     * {@code FailedCurrentSource} by the caller, never thrown) or the resolved transport inputs.
     *
     * <p>On success, this is the COMPLETE bundle of resolved transport inputs the caller needs to
     * build its HTTP client — {@code authFilter}, {@code trustStore}, AND {@code insecureSkipTlsVerify}
     * — even though the caller already holds the raw {@code insecureSkipTlsVerify} flag it passed
     * into {@link #resolve}. This is deliberate: {@code insecureSkipTlsVerify} is echoed back
     * unchanged so every caller (today {@code HttpCurrentSourceFactory}, later
     * {@code HttpHeaderCurrentSourceFactory}) can hand this one record straight to its client
     * factory instead of re-threading its own local copy of the flag alongside it. One object in,
     * one object out.
     *
     * <p>On {@link #failed}, {@code insecureSkipTlsVerify} is always {@code false} — a placeholder,
     * not a resolved value. It is unreachable by construction: every caller of {@link #resolve}
     * MUST check {@link #failureMessage} first and short-circuit (log + {@code FailedCurrentSource})
     * before reading any other field, exactly as {@code HttpCurrentSourceFactory} does. Nothing in
     * this class ever reads {@code insecureSkipTlsVerify} off a failed {@code Resolution}.
     */
    public record Resolution(
            Optional<String> failureMessage,
            Optional<ClientRequestFilter> authFilter,
            Optional<KeyStore> trustStore,
            boolean insecureSkipTlsVerify) {

        static Resolution failed(String message) {
            return new Resolution(Optional.of(message), Optional.empty(), Optional.empty(), false);
        }

        static Resolution resolved(
                Optional<ClientRequestFilter> authFilter, Optional<KeyStore> trustStore,
                boolean insecureSkipTlsVerify) {
            return new Resolution(Optional.empty(), authFilter, trustStore, insecureSkipTlsVerify);
        }
    }

    /**
     * Resolves the auth/TLS part of a config fragment. Checks, in order: {@code ca-cert} together with
     * {@code insecure-skip-tls-verify: true} is refused as ambiguous BEFORE any file is resolved; then
     * {@code ca-cert} is resolved into an in-memory truststore holding only the supplied CA(s); then, if
     * {@code insecure-skip-tls-verify} is enabled, a WARN is logged; then, if {@code auth} is present,
     * its value is validated and the matching {@link ClientRequestFilter} built.
     */
    public Resolution resolve(
            Optional<ApplicationConfigLoader.VersionSource.Auth> auth, Optional<String> caCert,
            boolean insecureSkipTlsVerify, String url) {

        if (insecureSkipTlsVerify && caCert.isPresent()) {
            String message = "The '" + kindLabel + "' current source has both 'ca-cert' and "
                    + "'insecure-skip-tls-verify: true'; this is ambiguous and refused, no "
                    + "precedence rule (url: '" + url + "').";
            return Resolution.failed(message);
        }

        CaCertResolution caCertResolution = resolveCaCert(caCert, url);
        if (caCertResolution.failureMessage().isPresent()) {
            return Resolution.failed(caCertResolution.failureMessage().get());
        }
        Optional<KeyStore> trustStore = caCertResolution.trustStore();

        if (insecureSkipTlsVerify) {
            logger.warn("The '" + kindLabel + "' current source has 'insecure-skip-tls-verify' "
                    + "enabled; TLS certificate and hostname verification are disabled for url '"
                    + url + "'.");
        }

        if (auth.isEmpty()) {
            return Resolution.resolved(Optional.empty(), trustStore, insecureSkipTlsVerify);
        }

        ApplicationConfigLoader.VersionSource.Auth authValue = auth.get();
        Optional<String> failureMessage = validateAuthValue(authValue, url);
        if (failureMessage.isPresent()) {
            return Resolution.failed(failureMessage.get());
        }

        ClientRequestFilter authFilter = buildAuthFilter(authValue);
        return Resolution.resolved(Optional.of(authFilter), trustStore, insecureSkipTlsVerify);
    }

    /**
     * Validates an auth fragment that IS present. Returns a clear failure message when {@code type}
     * is anything other than {@code basic}/{@code bearer}, or the type-specific credentials are
     * missing/blank ({@code basic} needs a username and password; {@code bearer} needs a token);
     * empty when the fragment is valid.
     */
    private Optional<String> validateAuthValue(
            ApplicationConfigLoader.VersionSource.Auth auth, String url) {
        if (BASIC_AUTH_TYPE.equals(auth.type())) {
            if (nonBlank(auth.username()).isEmpty() || nonBlank(auth.password()).isEmpty()) {
                return Optional.of("The '" + kindLabel + "' current source's auth.type 'basic' is "
                        + "missing a username or password (url: '" + url + "').");
            }
            return Optional.empty();
        }
        if (BEARER_AUTH_TYPE.equals(auth.type())) {
            boolean hasToken = nonBlank(auth.token()).isPresent();
            boolean hasTokenFile = nonBlank(auth.tokenFile()).isPresent();
            if (hasToken && hasTokenFile) {
                return Optional.of("The '" + kindLabel + "' current source's auth.type 'bearer' has "
                        + "both a token and a token-file; this is ambiguous and refused, no "
                        + "precedence rule (url: '" + url + "').");
            }
            if (!hasToken && !hasTokenFile) {
                return Optional.of("The '" + kindLabel + "' current source's auth.type 'bearer' needs "
                        + "a token or token-file (url: '" + url + "').");
            }
            return Optional.empty();
        }
        return Optional.of("The '" + kindLabel + "' current source's auth.type '" + auth.type()
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
     * not parseable as X.509/yields zero certs → a value-level failure (WARN + {@code FailedCurrentSource}
     * by the caller), NEVER a thrown exception. On success the parsed certs are loaded into a fresh
     * in-memory {@link KeyStore} ({@code load(null, null)}, holding ONLY the supplied CA(s) — it
     * REPLACES, not augments, the JVM bundle for this client only).
     */
    private CaCertResolution resolveCaCert(Optional<String> caCert, String url) {
        if (caCert.isEmpty()) {
            return CaCertResolution.noTrustStore();
        }
        if (nonBlank(caCert).isEmpty()) {
            return CaCertResolution.failed("The '" + kindLabel + "' current source's 'ca-cert' is "
                    + "configured but blank (url: '" + url + "').");
        }
        Path path = Path.of(caCert.get());
        Collection<? extends Certificate> certs;
        try (InputStream in = Files.newInputStream(path)) {
            certs = CertificateFactory.getInstance("X.509").generateCertificates(in);
        } catch (Exception ex) {
            return CaCertResolution.failed("The '" + kindLabel + "' current source's 'ca-cert' could "
                    + "not be read as X.509 PEM from '" + path + "' (url: '" + url + "'): "
                    + ex.getMessage());
        }
        if (certs.isEmpty()) {
            return CaCertResolution.failed("The '" + kindLabel + "' current source's 'ca-cert' at '"
                    + path + "' contained no X.509 certificates (url: '" + url + "').");
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
            return CaCertResolution.failed("The '" + kindLabel + "' current source's 'ca-cert' from '"
                    + path + "' could not be loaded into a truststore (url: '" + url + "'): "
                    + ex.getMessage());
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
}
