package org.yardship.unit.adapters.out.versionsource.current.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import jakarta.ws.rs.client.ClientRequestFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader.VersionSource.Auth;
import org.yardship.adapters.out.versionsource.auth.BasicAuthFilter;
import org.yardship.adapters.out.versionsource.auth.BearerAuthFilter;
import org.yardship.adapters.out.versionsource.auth.FileBearerAuthFilter;
import org.yardship.adapters.out.versionsource.current.http.HttpCurrentVersionClient;
import org.yardship.adapters.out.versionsource.current.http.HttpCurrentVersionClientFactory;
import org.yardship.adapters.out.versionsource.current.FailedCurrentSource;
import org.yardship.adapters.out.versionsource.current.http.HttpCurrentSource;
import org.yardship.adapters.out.versionsource.current.http.HttpCurrentSourceFactory;
import org.yardship.core.ports.out.CurrentVersionSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Unit tests for {@link HttpCurrentSourceFactory} — the factory for the {@code http} current-version
 * kind. Verifies its discriminator, its own config-fragment validation (the {@code http} kind
 * requires a {@code url}; {@code version-key} — if present — must be a syntactically valid JSON
 * Pointer), and — new in this slice — that it builds the {@link HttpCurrentVersionClient} EAGERLY during
 * {@code create(cfg)} via an injected {@link HttpCurrentVersionClientFactory} collaborator, rather than
 * lazily inside the source. A FAKE collaborator is used so this stays a true POJO unit test: no Arc,
 * no {@code @QuarkusTest}. The real, REST-client-backed collaborator is exercised at the integration
 * level ({@code HttpCurrentVersionClientFactoryIT}).
 */
class HttpCurrentSourceFactoryTests {

    private final FakeHttpCurrentVersionClientFactory clientFactory = new FakeHttpCurrentVersionClientFactory();
    private final HttpCurrentSourceFactory factory = new HttpCurrentSourceFactory(clientFactory);

    @Test
    void type_isHttp() {
        assertEquals("http", factory.type());
    }

    @Test
    void create_buildsASource_whenUrlIsPresent() {
        assertNotNull(factory.create(source(Optional.of("http://localhost:8089/current"), Optional.empty(), Optional.empty())));
    }

    @Test
    void create_buildsTheClientEagerly_viaTheCollaborator_withTheResolvedUrl() {
        factory.create(source(Optional.of("http://localhost:8089/current"), Optional.empty(), Optional.empty()));

        assertEquals(1, clientFactory.buildCalls.size(),
                "the client must be built eagerly during create(cfg), not lazily on first version()");
        assertEquals("http://localhost:8089/current", clientFactory.buildCalls.get(0));
    }

    @Test
    void create_rejectsAbsentUrl_withAClearMessage() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.create(source(Optional.empty(), Optional.empty(), Optional.empty())));
        assertTrue(ex.getMessage().toLowerCase().contains("url"),
                "the validation error must mention the missing 'url'; was: " + ex.getMessage());
    }

    @Test
    void create_rejectsAbsentUrl_withoutInvokingTheCollaborator() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.create(source(Optional.empty(), Optional.empty(), Optional.empty())));

        assertTrue(clientFactory.buildCalls.isEmpty(),
                "validation must fail before any client is built");
    }

    @Test
    void create_rejectsBlankUrl_withAClearMessage() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.create(source(Optional.of("   "), Optional.empty(), Optional.empty())));
    }

    @Test
    void create_defaultsVersionKey_toSlashVersion_whenAbsent() {
        // No 'version-key' configured: the factory must still construct a source successfully,
        // defaulting the pointer to '/version' so existing {"version":"…"} endpoints keep working.
        assertNotNull(factory.create(
                source(Optional.of("http://localhost:8089/current"), Optional.empty(), Optional.empty())));
    }

    @Test
    void create_acceptsAConfiguredVersionKey() {
        assertNotNull(factory.create(
                source(Optional.of("http://localhost:8089/current"), Optional.of("/harbor_version"), Optional.empty())));
    }

    @Test
    void create_rejectsASyntacticallyInvalidVersionKey_withAClearMessage() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.create(
                        source(Optional.of("http://localhost:8089/current"), Optional.of("harbor_version"), Optional.empty())));
        assertTrue(ex.getMessage().contains("harbor_version"),
                "the validation error must name the bad pointer; was: " + ex.getMessage());
    }

    @Test
    void create_rejectsASyntacticallyInvalidVersionKey_withoutInvokingTheCollaborator() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.create(
                        source(Optional.of("http://localhost:8089/current"), Optional.of("harbor_version"), Optional.empty())));

        assertTrue(clientFactory.buildCalls.isEmpty(),
                "version-key validation must fail before any client is built");
    }

    @Test
    void create_buildsASource_whenStripPrereleaseIsAbsent_defaultingToFalse() {
        // No 'strip-prerelease' configured: the factory must still construct a source successfully,
        // defaulting to false so prerelease segments are preserved for every existing app.
        assertNotNull(factory.create(
                source(Optional.of("http://localhost:8089/current"), Optional.empty(), Optional.empty())));
    }

    @Test
    void create_buildsASource_whenStripPrereleaseIsExplicitlyTrue() {
        assertNotNull(factory.create(
                source(Optional.of("http://localhost:8089/current"), Optional.empty(), Optional.of(true))));
    }

    @Test
    void create_buildsASource_whenStripPrereleaseIsExplicitlyFalse() {
        assertNotNull(factory.create(
                source(Optional.of("http://localhost:8089/current"), Optional.empty(), Optional.of(false))));
    }

    // --- Issue 02: auth resolution (Harbor case study) ----------------------------------------

    @Test
    void create_withNoAuth_stillBuildsAnHttpCurrentSource_withNoFilter() {
        // Existing (slice 01) behaviour must be preserved unchanged when 'auth' is absent.
        CurrentVersionSource result = factory.create(
                sourceWithAuth("http://localhost:8089/current", Optional.empty()));

        assertInstanceOf(HttpCurrentSource.class, result);
        assertEquals(1, clientFactory.buildCalls.size());
        assertEquals(Optional.empty(), clientFactory.lastAuthFilter);
    }

    @Test
    void create_withValidBasicAuth_buildsTheClient_withABasicAuthFilter() {
        Auth basic = auth("basic", Optional.of("harbor-bot"), Optional.of("s3cr3t"), Optional.empty());

        CurrentVersionSource result = factory.create(
                sourceWithAuth("http://localhost:8089/systeminfo", Optional.of(basic)));

        assertInstanceOf(HttpCurrentSource.class, result,
                "valid basic auth must still produce a real HttpCurrentSource, not a FailedCurrentSource");
        assertEquals(1, clientFactory.buildCalls.size());
        assertTrue(clientFactory.lastAuthFilter.isPresent(),
                "the collaborator must be called with a present auth filter for valid basic auth");
        assertInstanceOf(BasicAuthFilter.class, clientFactory.lastAuthFilter.get());
    }

    @Test
    void create_withUnknownAuthType_returnsAFailedCurrentSource_withoutInvokingTheCollaborator() {
        Auth unknown = auth("oauth2", Optional.of("user"), Optional.of("pass"), Optional.empty());

        CurrentVersionSource result = factory.create(
                sourceWithAuth("http://localhost:8089/systeminfo", Optional.of(unknown)));

        assertInstanceOf(FailedCurrentSource.class, result);
        assertTrue(clientFactory.buildCalls.isEmpty(),
                "an unsupported auth type must fail before any client is built");
    }

    @Test
    void create_withBasicAuthMissingUsername_returnsAFailedCurrentSource() {
        Auth missingUsername = auth("basic", Optional.empty(), Optional.of("s3cr3t"), Optional.empty());

        CurrentVersionSource result = factory.create(
                sourceWithAuth("http://localhost:8089/systeminfo", Optional.of(missingUsername)));

        assertInstanceOf(FailedCurrentSource.class, result);
        assertTrue(clientFactory.buildCalls.isEmpty());
    }

    @Test
    void create_withBasicAuthMissingPassword_returnsAFailedCurrentSource() {
        Auth missingPassword = auth("basic", Optional.of("harbor-bot"), Optional.empty(), Optional.empty());

        CurrentVersionSource result = factory.create(
                sourceWithAuth("http://localhost:8089/systeminfo", Optional.of(missingPassword)));

        assertInstanceOf(FailedCurrentSource.class, result);
        assertTrue(clientFactory.buildCalls.isEmpty());
    }

    @Test
    void create_withBasicAuthBlankUsername_returnsAFailedCurrentSource() {
        // An unset env var (e.g. ${HARBOR_USER:}) resolves to "" via SmallRye expansion, not absent —
        // blank must be treated the same as missing.
        Auth blankUsername = auth("basic", Optional.of("   "), Optional.of("s3cr3t"), Optional.empty());

        CurrentVersionSource result = factory.create(
                sourceWithAuth("http://localhost:8089/systeminfo", Optional.of(blankUsername)));

        assertInstanceOf(FailedCurrentSource.class, result);
        assertTrue(clientFactory.buildCalls.isEmpty());
    }

    @Test
    void create_withBasicAuthBlankPassword_returnsAFailedCurrentSource() {
        Auth blankPassword = auth("basic", Optional.of("harbor-bot"), Optional.of(""), Optional.empty());

        CurrentVersionSource result = factory.create(
                sourceWithAuth("http://localhost:8089/systeminfo", Optional.of(blankPassword)));

        assertInstanceOf(FailedCurrentSource.class, result);
        assertTrue(clientFactory.buildCalls.isEmpty());
    }

    // --- Issue 03: bearer auth resolution -----------------------------------------------------

    @Test
    void create_withValidBearerAuth_buildsTheClient_withABearerAuthFilter() {
        Auth bearer = authWithToken("bearer", Optional.of("gh-token"));

        CurrentVersionSource result = factory.create(
                sourceWithAuth("http://localhost:8089/current", Optional.of(bearer)));

        assertInstanceOf(HttpCurrentSource.class, result,
                "valid bearer auth must produce a real HttpCurrentSource, not a FailedCurrentSource");
        assertEquals(1, clientFactory.buildCalls.size());
        assertTrue(clientFactory.lastAuthFilter.isPresent(),
                "the collaborator must be called with a present auth filter for valid bearer auth");
        assertInstanceOf(BearerAuthFilter.class, clientFactory.lastAuthFilter.get());
    }

    @Test
    void create_withBearerAuthMissingToken_returnsAFailedCurrentSource_withoutInvokingTheCollaborator() {
        Auth missingToken = authWithToken("bearer", Optional.empty());

        CurrentVersionSource result = factory.create(
                sourceWithAuth("http://localhost:8089/current", Optional.of(missingToken)));

        assertInstanceOf(FailedCurrentSource.class, result);
        assertTrue(clientFactory.buildCalls.isEmpty(),
                "a missing bearer token must fail before any client is built");
    }

    @Test
    void create_withBearerAuthBlankToken_returnsAFailedCurrentSource() {
        // An unset env var (e.g. ${GH_TOKEN:}) resolves to "" via SmallRye expansion, not absent —
        // blank must be treated the same as missing, consistent with the basic-auth branch.
        Auth blankToken = authWithToken("bearer", Optional.of(""));

        CurrentVersionSource result = factory.create(
                sourceWithAuth("http://localhost:8089/current", Optional.of(blankToken)));

        assertInstanceOf(FailedCurrentSource.class, result);
        assertTrue(clientFactory.buildCalls.isEmpty());
    }

    @Test
    void create_withBearerAuthWhitespaceToken_returnsAFailedCurrentSource() {
        Auth whitespaceToken = authWithToken("bearer", Optional.of("   "));

        CurrentVersionSource result = factory.create(
                sourceWithAuth("http://localhost:8089/current", Optional.of(whitespaceToken)));

        assertInstanceOf(FailedCurrentSource.class, result);
        assertTrue(clientFactory.buildCalls.isEmpty());
    }

    // --- Issue 01: token-file bearer resolution ----------------------------------------------

    @Test
    void create_withBearerTokenFileOnly_buildsTheClient_withAFileBearerAuthFilter() {
        Auth tokenFile = authWithTokenFile("bearer", Optional.of("/var/run/secrets/token"));

        CurrentVersionSource result = factory.create(
                sourceWithAuth("http://localhost:8089/current", Optional.of(tokenFile)));

        assertInstanceOf(HttpCurrentSource.class, result,
                "bearer with only token-file must produce a real HttpCurrentSource");
        assertEquals(1, clientFactory.buildCalls.size());
        assertTrue(clientFactory.lastAuthFilter.isPresent(),
                "the collaborator must be called with a present auth filter for a token-file bearer");
        assertInstanceOf(FileBearerAuthFilter.class, clientFactory.lastAuthFilter.get());
    }

    @Test
    void create_withBearerTokenOnly_buildsTheClient_withAPlainBearerAuthFilter() {
        // The static token path is unchanged: token set, token-file unset → BearerAuthFilter.
        Auth token = authWithToken("bearer", Optional.of("gh-token"));

        CurrentVersionSource result = factory.create(
                sourceWithAuth("http://localhost:8089/current", Optional.of(token)));

        assertInstanceOf(HttpCurrentSource.class, result);
        assertEquals(1, clientFactory.buildCalls.size());
        assertTrue(clientFactory.lastAuthFilter.isPresent());
        assertInstanceOf(BearerAuthFilter.class, clientFactory.lastAuthFilter.get());
    }

    @Test
    void create_withBearerNeitherTokenNorTokenFile_returnsAFailedCurrentSource_withoutInvokingTheCollaborator() {
        Auth neither = auth("bearer", Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        CurrentVersionSource result = factory.create(
                sourceWithAuth("http://localhost:8089/current", Optional.of(neither)));

        assertInstanceOf(FailedCurrentSource.class, result);
        assertTrue(clientFactory.buildCalls.isEmpty(),
                "bearer with neither token nor token-file must fail before any client is built");
    }

    @Test
    void create_withBearerBothTokenAndTokenFile_returnsAFailedCurrentSource_withoutInvokingTheCollaborator_andDoesNotThrow() {
        // Ambiguous: both set is refused, no precedence rule. This must be a returned
        // FailedCurrentSource (a value problem), never a thrown exception.
        Auth both = auth("bearer", Optional.empty(), Optional.empty(),
                Optional.of("gh-token"), Optional.of("/var/run/secrets/token"));

        CurrentVersionSource result = factory.create(
                sourceWithAuth("http://localhost:8089/current", Optional.of(both)));

        assertInstanceOf(FailedCurrentSource.class, result);
        assertTrue(clientFactory.buildCalls.isEmpty(),
                "bearer with both token and token-file is ambiguous and must fail before any client is built");
    }

    @Test
    void create_withBearerBlankTokenFile_returnsAFailedCurrentSource() {
        // A blank path string is the same value-error bucket as a missing token.
        Auth blankPath = authWithTokenFile("bearer", Optional.of("   "));

        CurrentVersionSource result = factory.create(
                sourceWithAuth("http://localhost:8089/current", Optional.of(blankPath)));

        assertInstanceOf(FailedCurrentSource.class, result);
        assertTrue(clientFactory.buildCalls.isEmpty());
    }

    // --- Issue 02: ca-cert (per-scraper custom truststore) ------------------------------------

    /**
     * A valid self-signed X.509 certificate in PEM form (generated once via
     * {@code openssl req -x509 -newkey rsa:2048 -days 36500 -nodes}). Used as the on-disk PEM the
     * factory must parse into an in-memory truststore. The expiry is set far in the future so this
     * fixture does not rot.
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
    void create_withNoCaCert_buildsTheClient_withAnEmptyTrustStore() {
        // ca-cert absent: the JVM default trust bundle stays in place — no custom truststore is built
        // and the collaborator is handed Optional.empty().
        CurrentVersionSource result = factory.create(
                sourceWithCaCert("http://localhost:8089/current", Optional.empty()));

        assertInstanceOf(HttpCurrentSource.class, result);
        assertEquals(1, clientFactory.buildCalls.size());
        assertEquals(Optional.empty(), clientFactory.lastTrustStore,
                "absent ca-cert must keep the JVM default trust (Optional.empty() truststore)");
    }

    @Test
    void create_withValidCaCertPem_buildsTheClient_withAPresentTrustStoreHoldingTheCert(@TempDir Path dir)
            throws IOException, Exception {
        Path pem = dir.resolve("ca.crt");
        Files.writeString(pem, VALID_CA_PEM);

        CurrentVersionSource result = factory.create(
                sourceWithCaCert("https://localhost:8443/current", Optional.of(pem.toString())));

        assertInstanceOf(HttpCurrentSource.class, result,
                "a valid ca-cert PEM must still produce a real HttpCurrentSource, not a FailedCurrentSource");
        assertEquals(1, clientFactory.buildCalls.size());
        assertTrue(clientFactory.lastTrustStore.isPresent(),
                "a valid ca-cert PEM must be parsed into a present in-memory truststore");

        KeyStore trustStore = clientFactory.lastTrustStore.get();
        assertTrue(containsTheConfiguredCa(trustStore),
                "the built truststore must contain ONLY the supplied CA cert (curl --cacert / replace semantics)");
    }

    @Test
    void create_withBlankCaCert_returnsAFailedCurrentSource_withoutInvokingTheCollaborator() {
        // present-but-blank is a value-level misconfiguration → FailedCurrentSource, not a boot crash.
        CurrentVersionSource result = factory.create(
                sourceWithCaCert("https://localhost:8443/current", Optional.of("   ")));

        assertInstanceOf(FailedCurrentSource.class, result);
        assertTrue(clientFactory.buildCalls.isEmpty(),
                "a blank ca-cert must fail before any client is built");
    }

    @Test
    void create_withMissingCaCertFile_returnsAFailedCurrentSource_withoutInvokingTheCollaborator() {
        CurrentVersionSource result = factory.create(
                sourceWithCaCert("https://localhost:8443/current",
                        Optional.of("/no/such/path/ca.crt")));

        assertInstanceOf(FailedCurrentSource.class, result);
        assertTrue(clientFactory.buildCalls.isEmpty(),
                "a missing ca-cert file must fail before any client is built");
    }

    @Test
    void create_withNonPemCaCertFile_returnsAFailedCurrentSource(@TempDir Path dir) throws IOException {
        Path notPem = dir.resolve("ca.crt");
        Files.writeString(notPem, "this is definitely not a PEM certificate");

        CurrentVersionSource result = factory.create(
                sourceWithCaCert("https://localhost:8443/current", Optional.of(notPem.toString())));

        assertInstanceOf(FailedCurrentSource.class, result);
        assertTrue(clientFactory.buildCalls.isEmpty(),
                "a file that is not parseable as X.509 must fail before any client is built");
    }

    @Test
    void create_withEmptyCaCertFile_yieldingZeroCerts_returnsAFailedCurrentSource(@TempDir Path dir)
            throws IOException {
        // An empty (zero-cert) file is a value problem: a truststore with no CA would trust nothing.
        Path empty = dir.resolve("ca.crt");
        Files.writeString(empty, "");

        CurrentVersionSource result = factory.create(
                sourceWithCaCert("https://localhost:8443/current", Optional.of(empty.toString())));

        assertInstanceOf(FailedCurrentSource.class, result);
        assertTrue(clientFactory.buildCalls.isEmpty(),
                "a PEM yielding zero certs must fail before any client is built");
    }

    @Test
    void create_withABadCaCert_doesNotThrow_soOneBadAppCannotBlockTheOthersAtStartup() {
        // Same eager-construction isolation guarantee as the auth branch: a CA value problem must
        // return a FailedCurrentSource, never throw out of create(cfg).
        assertDoesNotThrow(() -> factory.create(
                sourceWithCaCert("https://localhost:8443/current",
                        Optional.of("/no/such/path/ca.crt"))));
    }

    @Test
    void create_withFailedAuth_doesNotThrow_soOneBadAppCannotBlockTheOthersAtStartup() {
        // VersionSourceResolver builds every app's sources eagerly at CDI construction; a thrown
        // exception here (rather than a returned FailedCurrentSource) would take down the whole
        // resolver, defeating per-app isolation. create(cfg) must return, never throw, for a VALUE
        // problem in auth.
        Auth unknown = auth("oauth2", Optional.empty(), Optional.empty(), Optional.empty());

        assertNotNull(factory.create(sourceWithAuth("http://localhost:8089/systeminfo", Optional.of(unknown))));
    }

    private static ApplicationConfigLoader.VersionSource source(
            Optional<String> url, Optional<String> versionKey, Optional<Boolean> stripPrerelease) {
        return source(url, versionKey, stripPrerelease, Optional.empty());
    }

    private static ApplicationConfigLoader.VersionSource sourceWithAuth(String url, Optional<Auth> auth) {
        return source(Optional.of(url), Optional.empty(), Optional.empty(), auth);
    }

    private static ApplicationConfigLoader.VersionSource sourceWithCaCert(String url, Optional<String> caCert) {
        return source(Optional.of(url), Optional.empty(), Optional.empty(), Optional.empty(), caCert);
    }

    private static ApplicationConfigLoader.VersionSource source(
            Optional<String> url, Optional<String> versionKey, Optional<Boolean> stripPrerelease,
            Optional<Auth> auth) {
        return source(url, versionKey, stripPrerelease, auth, Optional.empty());
    }

    private static ApplicationConfigLoader.VersionSource source(
            Optional<String> url, Optional<String> versionKey, Optional<Boolean> stripPrerelease,
            Optional<Auth> auth, Optional<String> caCert) {
        return new ApplicationConfigLoader.VersionSource() {
            @Override
            public String type() {
                return "http";
            }

            @Override
            public Optional<String> url() {
                return url;
            }

            @Override
            public Optional<String> repo() {
                return Optional.empty();
            }

            @Override
            public Optional<String> namespace() {
                return Optional.empty();
            }

            @Override
            public Optional<String> workload() {
                return Optional.empty();
            }

            @Override
            public Optional<String> container() {
                return Optional.empty();
            }

            @Override
            public Optional<String> versionKey() {
                return versionKey;
            }

            @Override
            public Optional<Boolean> stripPrerelease() {
                return stripPrerelease;
            }

            @Override
            public Optional<Auth> auth() {
                return auth;
            }

            @Override
            public Optional<Integer> pageSize() {
                return Optional.empty();
            }

            @Override
            public Optional<Integer> maxTags() {
                return Optional.empty();
            }

            @Override
            public Optional<String> caCert() {
                return caCert;
            }

            @Override
            public Optional<String> registry() {
                return Optional.empty();
            }

            @Override
            public Optional<String> prereleaseFilter() {
                return Optional.empty();
            }
        };
    }

    /**
     * Asserts the truststore holds exactly the supplied CA: at least one entry, and every entry is the
     * X.509 cert parsed from {@link #VALID_CA_PEM} (replace, not augment — no JVM default certs leaked
     * in).
     */
    private static boolean containsTheConfiguredCa(KeyStore trustStore) throws Exception {
        Certificate expected;
        try (var in = new java.io.ByteArrayInputStream(VALID_CA_PEM.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            expected = java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
        var aliases = Collections.list(trustStore.aliases());
        if (aliases.isEmpty()) {
            return false;
        }
        for (String alias : aliases) {
            if (!expected.equals(trustStore.getCertificate(alias))) {
                return false;
            }
        }
        return true;
    }

    private static Auth authWithToken(String type, Optional<String> token) {
        return auth(type, Optional.empty(), Optional.empty(), token, Optional.empty());
    }

    private static Auth authWithTokenFile(String type, Optional<String> tokenFile) {
        return auth(type, Optional.empty(), Optional.empty(), Optional.empty(), tokenFile);
    }

    private static Auth auth(
            String type, Optional<String> username, Optional<String> password, Optional<String> token) {
        return auth(type, username, password, token, Optional.empty());
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

    /**
     * Fake {@link HttpCurrentVersionClientFactory} collaborator: records every
     * {@code build(url, filter, trustStore)} invocation (so tests can assert eagerness + the resolved
     * url, the auth filter, and the custom truststore handed in) and returns a trivial
     * {@link HttpCurrentVersionClient} stub without touching Arc or the network.
     */
    private static class FakeHttpCurrentVersionClientFactory extends HttpCurrentVersionClientFactory {

        private final List<String> buildCalls = new ArrayList<>();
        private Optional<ClientRequestFilter> lastAuthFilter = Optional.empty();
        private Optional<KeyStore> lastTrustStore = Optional.empty();

        @Override
        public HttpCurrentVersionClient build(
                String url, Optional<ClientRequestFilter> authFilter, Optional<KeyStore> trustStore) {
            buildCalls.add(url);
            lastAuthFilter = authFilter;
            lastTrustStore = trustStore;
            return stubClient();
        }

        private static HttpCurrentVersionClient stubClient() {
            JsonNode empty = NullNode.getInstance();
            return () -> empty;
        }
    }
}
