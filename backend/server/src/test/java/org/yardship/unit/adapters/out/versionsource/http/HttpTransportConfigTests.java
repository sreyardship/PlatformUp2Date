package org.yardship.unit.adapters.out.versionsource.http;

import jakarta.ws.rs.client.ClientRequestFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader.VersionSource.Auth;
import org.yardship.adapters.out.versionsource.auth.BasicAuthFilter;
import org.yardship.adapters.out.versionsource.auth.BearerAuthFilter;
import org.yardship.adapters.out.versionsource.auth.FileBearerAuthFilter;
import org.yardship.adapters.out.versionsource.http.HttpTransportConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link HttpTransportConfig} — the stateless, kind-labelled collaborator
 * shared by every current-leg HTTP factory ({@code http-json} today, {@code http-header} in a later
 * slice). It resolves the {@code auth} / {@code ca-cert} / {@code insecure-skip-tls-verify} part of
 * a config fragment into either a value-level failure message or the resolved transport inputs: an
 * optional {@link ClientRequestFilter}, an optional truststore, and the insecure-TLS flag.
 *
 * <p>These tests were extracted from {@code HttpJsonCurrentSourceFactoryTests}, which stays as the
 * behaviour-preservation proof for the {@code http-json} factory itself (unmodified). This file exercises
 * the same rules directly through the new collaborator's own seam, plus the kind-label
 * parameterisation that {@code HttpJsonCurrentSourceFactoryTests} — fixed to {@code "http-json"} — cannot
 * observe.
 */
class HttpTransportConfigTests {

    private static final String URL = "https://localhost:8443/current";

    private final HttpTransportConfig transportConfig = new HttpTransportConfig("http-json");

    // --- No auth, no ca-cert: the fully-absent fragment ---------------------------------------

    @Test
    void resolve_withNoAuthAndNoCaCert_succeeds_withEmptyAuthFilter_emptyTrustStore_andInsecureFalse() {
        HttpTransportConfig.Resolution resolution =
                transportConfig.resolve(Optional.empty(), Optional.empty(), false, URL);

        assertTrue(resolution.failureMessage().isEmpty());
        assertEquals(Optional.empty(), resolution.authFilter());
        assertEquals(Optional.empty(), resolution.trustStore());
        assertFalse(resolution.insecureSkipTlsVerify());
    }

    // --- auth.type: basic -----------------------------------------------------------------------

    @Test
    void resolve_withValidBasicAuth_succeeds_withABasicAuthFilter() {
        Auth basic = auth("basic", Optional.of("harbor-bot"), Optional.of("s3cr3t"), Optional.empty(), Optional.empty());

        HttpTransportConfig.Resolution resolution =
                transportConfig.resolve(Optional.of(basic), Optional.empty(), false, URL);

        assertTrue(resolution.failureMessage().isEmpty());
        assertTrue(resolution.authFilter().isPresent());
        assertInstanceOf(BasicAuthFilter.class, resolution.authFilter().get());
    }

    @Test
    void resolve_withBasicAuthMissingUsername_returnsAFailureMessage() {
        Auth missingUsername = auth("basic", Optional.empty(), Optional.of("s3cr3t"), Optional.empty(), Optional.empty());

        HttpTransportConfig.Resolution resolution =
                transportConfig.resolve(Optional.of(missingUsername), Optional.empty(), false, URL);

        // Representative case pinning the exact wording (per basic-missing-credential branch): this is
        // the wording slice 03 must reproduce verbatim for 'http-header'.
        assertEquals(
                "The 'http-json' current source's auth.type 'basic' is missing a username or password "
                        + "(url: '" + URL + "').",
                resolution.failureMessage().orElseThrow());
    }

    @Test
    void resolve_withBasicAuthMissingPassword_returnsAFailureMessage() {
        Auth missingPassword = auth("basic", Optional.of("harbor-bot"), Optional.empty(), Optional.empty(), Optional.empty());

        HttpTransportConfig.Resolution resolution =
                transportConfig.resolve(Optional.of(missingPassword), Optional.empty(), false, URL);

        assertTrue(resolution.failureMessage().isPresent());
    }

    @Test
    void resolve_withBasicAuthBlankUsername_returnsAFailureMessage() {
        // Blank must be treated the same as missing (SmallRye expansion of an unset env var yields "").
        Auth blankUsername = auth("basic", Optional.of("   "), Optional.of("s3cr3t"), Optional.empty(), Optional.empty());

        HttpTransportConfig.Resolution resolution =
                transportConfig.resolve(Optional.of(blankUsername), Optional.empty(), false, URL);

        assertTrue(resolution.failureMessage().isPresent());
    }

    @Test
    void resolve_withBasicAuthBlankPassword_returnsAFailureMessage() {
        Auth blankPassword = auth("basic", Optional.of("harbor-bot"), Optional.of(""), Optional.empty(), Optional.empty());

        HttpTransportConfig.Resolution resolution =
                transportConfig.resolve(Optional.of(blankPassword), Optional.empty(), false, URL);

        assertTrue(resolution.failureMessage().isPresent());
    }

    // --- auth.type: bearer -----------------------------------------------------------------------

    @Test
    void resolve_withValidBearerToken_succeeds_withABearerAuthFilter() {
        Auth token = auth("bearer", Optional.empty(), Optional.empty(), Optional.of("gh-token"), Optional.empty());

        HttpTransportConfig.Resolution resolution =
                transportConfig.resolve(Optional.of(token), Optional.empty(), false, URL);

        assertTrue(resolution.failureMessage().isEmpty());
        assertTrue(resolution.authFilter().isPresent());
        assertInstanceOf(BearerAuthFilter.class, resolution.authFilter().get());
    }

    @Test
    void resolve_withValidBearerTokenFile_succeeds_withAFileBearerAuthFilter() {
        Auth tokenFile = auth("bearer", Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of("/var/run/secrets/token"));

        HttpTransportConfig.Resolution resolution =
                transportConfig.resolve(Optional.of(tokenFile), Optional.empty(), false, URL);

        assertTrue(resolution.failureMessage().isEmpty());
        assertTrue(resolution.authFilter().isPresent());
        assertInstanceOf(FileBearerAuthFilter.class, resolution.authFilter().get());
    }

    @Test
    void resolve_withBearerAuthNeitherTokenNorTokenFile_returnsAFailureMessage() {
        Auth neither = auth("bearer", Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        HttpTransportConfig.Resolution resolution =
                transportConfig.resolve(Optional.of(neither), Optional.empty(), false, URL);

        // Representative case pinning the exact wording (per bearer-neither branch): this is the
        // wording slice 03 must reproduce verbatim for 'http-header'.
        assertEquals(
                "The 'http-json' current source's auth.type 'bearer' needs a token or token-file "
                        + "(url: '" + URL + "').",
                resolution.failureMessage().orElseThrow());
    }

    @Test
    void resolve_withBearerAuthBothTokenAndTokenFile_returnsAFailureMessage() {
        // Ambiguous: both set is refused, no precedence rule.
        Auth both = auth("bearer", Optional.empty(), Optional.empty(), Optional.of("gh-token"),
                Optional.of("/var/run/secrets/token"));

        HttpTransportConfig.Resolution resolution =
                transportConfig.resolve(Optional.of(both), Optional.empty(), false, URL);

        // Representative case pinning the exact wording (per bearer-both branch): this is the wording
        // slice 03 must reproduce verbatim for 'http-header'.
        assertEquals(
                "The 'http-json' current source's auth.type 'bearer' has both a token and a token-file; "
                        + "this is ambiguous and refused, no precedence rule (url: '" + URL + "').",
                resolution.failureMessage().orElseThrow());
    }

    @Test
    void resolve_withBearerAuthBlankToken_returnsAFailureMessage() {
        Auth blankToken = auth("bearer", Optional.empty(), Optional.empty(), Optional.of(""), Optional.empty());

        HttpTransportConfig.Resolution resolution =
                transportConfig.resolve(Optional.of(blankToken), Optional.empty(), false, URL);

        assertTrue(resolution.failureMessage().isPresent());
    }

    @Test
    void resolve_withBearerAuthBlankTokenFile_returnsAFailureMessage() {
        Auth blankTokenFile = auth("bearer", Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("   "));

        HttpTransportConfig.Resolution resolution =
                transportConfig.resolve(Optional.of(blankTokenFile), Optional.empty(), false, URL);

        assertTrue(resolution.failureMessage().isPresent());
    }

    // --- unsupported auth.type -------------------------------------------------------------------

    @Test
    void resolve_withUnsupportedAuthType_returnsAFailureMessage() {
        Auth unknown = auth("oauth2", Optional.of("user"), Optional.of("pass"), Optional.empty(), Optional.empty());

        HttpTransportConfig.Resolution resolution =
                transportConfig.resolve(Optional.of(unknown), Optional.empty(), false, URL);

        // Representative case pinning the exact wording (per unsupported-type branch): this is the
        // wording slice 03 must reproduce verbatim for 'http-header'.
        assertEquals(
                "The 'http-json' current source's auth.type 'oauth2' is not supported (url: '" + URL + "').",
                resolution.failureMessage().orElseThrow());
    }

    // --- ca-cert ---------------------------------------------------------------------------------

    /**
     * A valid self-signed X.509 certificate in PEM form (generated once via
     * {@code openssl req -x509 -newkey rsa:2048 -days 36500 -nodes}). Used as the on-disk PEM the
     * collaborator must parse into an in-memory truststore. The expiry is set far in the future so
     * this fixture does not rot. Identical to the fixture used by {@code HttpJsonCurrentSourceFactoryTests}.
     */
    private static final String VALID_CA_PEM = """
            -----BEGIN CERTIFICATE-----
            MIIDJzCCAg+gAwIBAgIUR1x+4fXxTTaq4Q6/m0k+EKyb7agwDQYJKoZIhvcNAQEL
            BQAwIjEgMB4GA1UEAwwXUGxhdGZvcm1VcDJEYXRlIFRlc3QgQ0EwIBcNMjYwNjIy
            MDgwMTQzWhgPMjEyNjA1MjkwODAxNDNaMCIxIDAeBgNVBAMMF1BsYXRmb3JtVXAy
            RGF0ZSBUZXN0IENBMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAmEaM
            S9mdblCxtJrKOVxyeqmPRNndiFLSJFT59oxVt31hIMjrzvMVYkfjB9WPXBI5tlBu
            ueGiXQD1dLffprts8XY0XN+UDolvPgkuGgSH2jXUxYfkz60rE6SzG00z0nRAdAU6
            GWLv/FvyDMH4YpXEpa1xgD4CTdc7XT2noxyDa0fFjv/z2SNUFkp71nAC/IY1mt5F
            OafbjpC1yJSypJg6NJYEogNFs77AG7cFqAJYE898RI5FujfXG557DIIILqcxL3zq
            /jqLWYnXKWaBYxVPsO8uZLa7OWJpW4c4hSzsOPsW7WUEH+SUghEJEW2A8XDUXudm
            gk9xaYh7+L9ZC9D9ZwIDAQABo1MwUTAdBgNVHQ4EFgQUfzzYL7VwRa2W9rNIuHwb
            j8egbrAwHwYDVR0jBBgwFoAUfzzYL7VwRa2W9rNIuHwbj8egbrAwDwYDVR0TAQH/
            BAUwAwEB/zANBgkqhkiG9w0BAQsFAAOCAQEAemKKJFywsb++jnyFunb8iFpsD6Au
            h8qBZDCQagBAudFJKDDN4z/E8whNSjWW3JwDNN3lbwl44Pof/8EwRsT+jKLT+O8N
            WP7l2vyS0o39iobNwP0hvUwd/gnsdbJgaPY8zRzuFyriI/FvqlH0Bf7NdzrTB/Rx
            A35l0ZiOoLQZZnXGAnlVQ0it8lxWpDOpVFO4wJmj+RIPSFaxADiBgi7zvxrLVjQ+
            5JJDUXBuPFQvF1e3DRrUhRA589svl8oQ7Q/H8bKJ5OPmUcdG8zQwUtse9gSEkGfE
            JDf1IaPOy1klCu7jFaEiudcawTBdXI+uMkCvkvGEtN/ylsNUwJaMLIyB5A==
            -----END CERTIFICATE-----
            """;

    @Test
    void resolve_withNoCaCert_succeeds_withAnEmptyTrustStore() {
        HttpTransportConfig.Resolution resolution =
                transportConfig.resolve(Optional.empty(), Optional.empty(), false, URL);

        assertTrue(resolution.failureMessage().isEmpty());
        assertEquals(Optional.empty(), resolution.trustStore());
    }

    @Test
    void resolve_withValidCaCertPem_succeeds_withATrustStoreContainingOnlyTheSuppliedCa(@TempDir Path dir)
            throws Exception {
        Path pem = dir.resolve("ca.crt");
        Files.writeString(pem, VALID_CA_PEM);

        HttpTransportConfig.Resolution resolution =
                transportConfig.resolve(Optional.empty(), Optional.of(pem.toString()), false, URL);

        assertTrue(resolution.failureMessage().isEmpty());
        assertTrue(resolution.trustStore().isPresent());
        assertTrue(containsOnlyTheConfiguredCa(resolution.trustStore().get()),
                "the built truststore must contain ONLY the supplied CA cert (replace, not augment)");
    }

    @Test
    void resolve_withBlankCaCert_returnsAFailureMessage() {
        HttpTransportConfig.Resolution resolution =
                transportConfig.resolve(Optional.empty(), Optional.of("   "), false, URL);

        // Representative case pinning the exact wording (per ca-cert-blank branch): this is the
        // wording slice 03 must reproduce verbatim for 'http-header'.
        assertEquals(
                "The 'http-json' current source's 'ca-cert' is configured but blank (url: '" + URL + "').",
                resolution.failureMessage().orElseThrow());
    }

    @Test
    void resolve_withMissingCaCertFile_returnsAFailureMessage_notAnException() {
        HttpTransportConfig.Resolution resolution =
                transportConfig.resolve(Optional.empty(), Optional.of("/no/such/path/ca.crt"), false, URL);

        assertTrue(resolution.failureMessage().isPresent());
    }

    @Test
    void resolve_withNonPemCaCertFile_returnsAFailureMessage_notAnException(@TempDir Path dir) throws IOException {
        Path notPem = dir.resolve("ca.crt");
        Files.writeString(notPem, "this is definitely not a PEM certificate");

        HttpTransportConfig.Resolution resolution =
                transportConfig.resolve(Optional.empty(), Optional.of(notPem.toString()), false, URL);

        assertTrue(resolution.failureMessage().isPresent());
    }

    @Test
    void resolve_withEmptyCaCertFile_yieldingZeroCerts_returnsAFailureMessage(@TempDir Path dir) throws IOException {
        Path empty = dir.resolve("ca.crt");
        Files.writeString(empty, "");

        HttpTransportConfig.Resolution resolution =
                transportConfig.resolve(Optional.empty(), Optional.of(empty.toString()), false, URL);

        // Representative case pinning the exact wording (per ca-cert-zero-certs branch): this is the
        // wording slice 03 must reproduce verbatim for 'http-header'. The path is echoed via
        // java.nio.file.Path.of(...).toString(), matching how the collaborator renders it.
        assertEquals(
                "The 'http-json' current source's 'ca-cert' at '" + Path.of(empty.toString())
                        + "' contained no X.509 certificates (url: '" + URL + "').",
                resolution.failureMessage().orElseThrow());
    }

    // --- ca-cert plus insecure-skip-tls-verify: true is refused as ambiguous ------------------

    @Test
    void resolve_withCaCertAndInsecureSkipTlsVerifyTrue_returnsAFailureMessage_namingBothKeysAndTheUrl(
            @TempDir Path dir) throws IOException {
        Path pem = dir.resolve("ca.crt");
        Files.writeString(pem, VALID_CA_PEM);

        HttpTransportConfig.Resolution resolution =
                transportConfig.resolve(Optional.empty(), Optional.of(pem.toString()), true, URL);

        assertTrue(resolution.failureMessage().isPresent());
        String message = resolution.failureMessage().get();
        assertTrue(message.contains("ca-cert"), "message must name 'ca-cert'; was: " + message);
        assertTrue(message.contains("insecure-skip-tls-verify"),
                "message must name 'insecure-skip-tls-verify'; was: " + message);
        assertTrue(message.contains(URL), "message must name the url; was: " + message);
    }

    @Test
    void resolve_withCaCertAndInsecureSkipTlsVerifyTrue_usingAnUnvalidatedPath_isRefused_beforeAnyFileResolution() {
        // Refusal happens BEFORE ca-cert file resolution: even a nonexistent/garbage path must be
        // refused with the both-set message, not surfaced as a file-read failure.
        HttpTransportConfig.Resolution resolution =
                transportConfig.resolve(Optional.empty(), Optional.of("/no/such/path/ca.crt"), true, URL);

        assertTrue(resolution.failureMessage().isPresent());
        assertTrue(resolution.failureMessage().get().contains("insecure-skip-tls-verify"),
                "refusal must be the both-set message, not a ca-cert file-read failure; was: "
                        + resolution.failureMessage().get());
    }

    @Test
    void resolve_withCaCertAndInsecureSkipTlsVerifyExplicitlyFalse_succeeds_withATrustStore_andInsecureFalse(
            @TempDir Path dir) throws IOException {
        Path pem = dir.resolve("ca.crt");
        Files.writeString(pem, VALID_CA_PEM);

        HttpTransportConfig.Resolution resolution =
                transportConfig.resolve(Optional.empty(), Optional.of(pem.toString()), false, URL);

        assertTrue(resolution.failureMessage().isEmpty());
        assertTrue(resolution.trustStore().isPresent());
        assertFalse(resolution.insecureSkipTlsVerify());
    }

    // --- insecure-skip-tls-verify (no ca-cert) --------------------------------------------------

    @Test
    void resolve_withInsecureSkipTlsVerifyTrue_succeeds_withInsecureTrue() {
        HttpTransportConfig.Resolution resolution =
                transportConfig.resolve(Optional.empty(), Optional.empty(), true, URL);

        assertTrue(resolution.failureMessage().isEmpty());
        assertTrue(resolution.insecureSkipTlsVerify());
    }

    @Test
    void resolve_withInsecureSkipTlsVerifyFalse_succeeds_withInsecureFalse() {
        HttpTransportConfig.Resolution resolution =
                transportConfig.resolve(Optional.empty(), Optional.empty(), false, URL);

        assertTrue(resolution.failureMessage().isEmpty());
        assertFalse(resolution.insecureSkipTlsVerify());
    }

    // --- kind-label parameterisation --------------------------------------------------------------

    @Test
    void resolve_namesTheConfiguredKindLabel_inAuthFailureMessages_forHttp() {
        Auth unknown = auth("oauth2", Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        HttpTransportConfig httpConfig = new HttpTransportConfig("http-json");
        HttpTransportConfig.Resolution resolution =
                httpConfig.resolve(Optional.of(unknown), Optional.empty(), false, URL);

        assertTrue(resolution.failureMessage().get().contains("The 'http-json' current source's"),
                "was: " + resolution.failureMessage().get());
    }

    @Test
    void resolve_namesTheConfiguredKindLabel_inAuthFailureMessages_forHttpHeader() {
        Auth unknown = auth("oauth2", Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        HttpTransportConfig httpHeaderConfig = new HttpTransportConfig("http-header");
        HttpTransportConfig.Resolution resolution =
                httpHeaderConfig.resolve(Optional.of(unknown), Optional.empty(), false, URL);

        assertTrue(resolution.failureMessage().get().contains("The 'http-header' current source's"),
                "was: " + resolution.failureMessage().get());
    }

    @Test
    void resolve_namesTheConfiguredKindLabel_inTheCaCertAndInsecureAmbiguityMessage_forHttp() {
        HttpTransportConfig httpConfig = new HttpTransportConfig("http-json");
        HttpTransportConfig.Resolution resolution =
                httpConfig.resolve(Optional.empty(), Optional.of("/no/such/path/ca.crt"), true, URL);

        assertTrue(resolution.failureMessage().get().contains("The 'http-json' current source has"),
                "was: " + resolution.failureMessage().get());
    }

    @Test
    void resolve_namesTheConfiguredKindLabel_inTheCaCertAndInsecureAmbiguityMessage_forHttpHeader() {
        HttpTransportConfig httpHeaderConfig = new HttpTransportConfig("http-header");
        HttpTransportConfig.Resolution resolution =
                httpHeaderConfig.resolve(Optional.empty(), Optional.of("/no/such/path/ca.crt"), true, URL);

        assertTrue(resolution.failureMessage().get().contains("The 'http-header' current source has"),
                "was: " + resolution.failureMessage().get());
    }

    // --- helpers -----------------------------------------------------------------------------

    private static boolean containsOnlyTheConfiguredCa(KeyStore trustStore) throws Exception {
        Certificate expected;
        try (var in = new java.io.ByteArrayInputStream(VALID_CA_PEM.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            expected = java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
        var aliases = Collections.list(trustStore.aliases());
        if (aliases.size() != 1) {
            return false;
        }
        for (String alias : aliases) {
            if (!expected.equals(trustStore.getCertificate(alias))) {
                return false;
            }
        }
        return true;
    }

    private static Auth auth(
            String type, Optional<String> username, Optional<String> password, Optional<String> token,
            Optional<String> tokenFile) {
        return new Auth() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public Optional<String> username() {
                return username;
            }

            @Override
            public Optional<String> password() {
                return password;
            }

            @Override
            public Optional<String> token() {
                return token;
            }

            @Override
            public Optional<String> tokenFile() {
                return tokenFile;
            }
        };
    }
}
