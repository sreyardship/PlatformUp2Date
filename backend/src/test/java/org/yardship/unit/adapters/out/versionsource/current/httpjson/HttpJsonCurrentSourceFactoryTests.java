package org.yardship.unit.adapters.out.versionsource.current.httpjson;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader.VersionSource.Auth;
import org.yardship.adapters.out.versionsource.current.FailedCurrentSource;
import org.yardship.adapters.out.versionsource.current.HttpCurrentSource;
import org.yardship.adapters.out.versionsource.current.httpjson.HttpJsonCurrentSourceFactory;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import org.yardship.core.ports.out.CurrentVersionSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link HttpJsonCurrentSourceFactory} — the factory for the {@code http-json}
 * current-version kind. Verifies its discriminator, its own STRUCTURAL config-fragment validation
 * (a non-blank {@code url} is required; {@code version-key} — if present — must be a syntactically
 * valid JSON Pointer; both THROW an {@link IllegalArgumentException} from {@code create()}), and
 * that VALUE-level {@code auth} / {@code ca-cert} / {@code insecure-skip-tls-verify} problems are
 * routed through the shared, kind-labelled {@code HttpTransportConfig} into a
 * {@link FailedCurrentSource} whose message names {@code http-json}, never a thrown exception.
 *
 * <p>The exhaustive matrix of every individual {@code auth}/{@code ca-cert} value-error case is
 * owned by {@code HttpTransportConfigTests} against the shared collaborator directly, and what the
 * built transport does on the wire by {@code HttpTransportIT}; this class only proves the WIRING —
 * that this factory constructs the collaborator with the {@code "http-json"} kind label and maps
 * its outcome — matching {@code HttpHeaderCurrentSourceFactoryTests}' idiom.
 */
class HttpJsonCurrentSourceFactoryTests {

    private static final VersionParser SEMVER_PARSER = new VersionParser(VersionScheme.SEMVER);
    private static final String URL = "http://localhost:8089/current";

    private final HttpJsonCurrentSourceFactory factory = new HttpJsonCurrentSourceFactory();

    @Test
    void type_isHttpJson() {
        assertEquals("http-json", factory.type());
    }

    @Test
    void create_buildsAWorkingSource_forAMinimalValidFragment() {
        CurrentVersionSource result = factory.create(source(Optional.of(URL), Optional.empty()), SEMVER_PARSER);

        assertInstanceOf(HttpCurrentSource.class, result);
    }

    // --- structural: url --------------------------------------------------------------------

    @Test
    void create_throws_whenUrlIsAbsent_withAClearMessage() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.create(source(Optional.empty(), Optional.empty()), SEMVER_PARSER));

        assertTrue(ex.getMessage().toLowerCase().contains("url"),
                "the validation error must mention the missing 'url'; was: " + ex.getMessage());
    }

    @Test
    void create_throws_whenUrlIsBlank() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.create(source(Optional.of("   "), Optional.empty()), SEMVER_PARSER));
    }

    // --- structural: version-key ------------------------------------------------------------

    @Test
    void create_defaultsVersionKey_toSlashVersion_whenAbsent() {
        // No 'version-key' configured: the factory must still construct a source successfully,
        // defaulting the pointer to '/version' so existing {"version":"…"} endpoints keep working.
        assertInstanceOf(HttpCurrentSource.class,
                factory.create(source(Optional.of(URL), Optional.empty()), SEMVER_PARSER));
    }

    @Test
    void create_acceptsAConfiguredVersionKey() {
        assertInstanceOf(HttpCurrentSource.class,
                factory.create(source(Optional.of(URL), Optional.of("/harbor_version")), SEMVER_PARSER));
    }

    @Test
    void create_throws_whenVersionKeyIsNotAValidJsonPointer_namingTheBadPointer() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.create(source(Optional.of(URL), Optional.of("harbor_version")), SEMVER_PARSER));

        assertTrue(ex.getMessage().contains("harbor_version"),
                "the validation error must name the bad pointer; was: " + ex.getMessage());
    }

    // --- value-level: auth --------------------------------------------------------------------

    @Test
    void create_withAnInvalidAuthValue_returnsAFailedCurrentSource_withAMessageNamingHttpJson() {
        Auth unsupported = auth("oauth2", Optional.of("user"), Optional.of("pass"));

        CurrentVersionSource result = factory.create(sourceWithAuth(URL, unsupported), SEMVER_PARSER);

        assertInstanceOf(FailedCurrentSource.class, result);
        IllegalStateException ex = assertThrows(IllegalStateException.class, result::version);
        assertTrue(ex.getMessage().contains("http-json"),
                "the FailedCurrentSource message must name 'http-json'; was: " + ex.getMessage());
    }

    @Test
    void create_withAValidAuthValue_buildsAWorkingSource() {
        Auth basic = auth("basic", Optional.of("harbor-bot"), Optional.of("s3cr3t"));

        CurrentVersionSource result = factory.create(sourceWithAuth(URL, basic), SEMVER_PARSER);

        assertInstanceOf(HttpCurrentSource.class, result);
    }

    @Test
    void create_withFailedAuth_doesNotThrow_soOneBadAppCannotBlockTheOthersAtStartup() {
        // VersionSourceResolver builds every app's sources eagerly at CDI construction; a thrown
        // exception here (rather than a returned FailedCurrentSource) would take down the whole
        // resolver, defeating per-app isolation. create(cfg) must return, never throw, for a VALUE
        // problem in auth.
        Auth unsupported = auth("oauth2", Optional.empty(), Optional.empty());

        assertDoesNotThrow(() -> factory.create(sourceWithAuth(URL, unsupported), SEMVER_PARSER));
    }

    // --- value-level: ca-cert / insecure-skip-tls-verify ---------------------------------------

    @Test
    void create_withABlankCaCert_returnsAFailedCurrentSource_withAMessageNamingHttpJson() {
        CurrentVersionSource result = factory.create(sourceWithCaCert(URL, "   "), SEMVER_PARSER);

        assertInstanceOf(FailedCurrentSource.class, result);
        IllegalStateException ex = assertThrows(IllegalStateException.class, result::version);
        assertTrue(ex.getMessage().contains("http-json"),
                "the FailedCurrentSource message must name 'http-json'; was: " + ex.getMessage());
    }

    @Test
    void create_withAMissingCaCertFile_returnsAFailedCurrentSource_ratherThanThrowing() {
        CurrentVersionSource result = assertDoesNotThrow(
                () -> factory.create(sourceWithCaCert(URL, "/no/such/path/ca.crt"), SEMVER_PARSER));

        assertInstanceOf(FailedCurrentSource.class, result);
    }

    @Test
    void create_withCaCertAndInsecureSkipTlsVerifyTrue_returnsAFailedCurrentSource_namingBothKeysAndTheUrl() {
        String url = "https://localhost:8443/current";

        CurrentVersionSource result = factory.create(
                sourceWithCaCertAndInsecureSkipTlsVerify(url, "/no/such/path/ca.crt", true), SEMVER_PARSER);

        assertInstanceOf(FailedCurrentSource.class, result,
                "ca-cert together with insecure-skip-tls-verify: true is ambiguous and must be refused");
        String message = assertThrows(IllegalStateException.class, result::version).getMessage();
        assertTrue(message.contains("ca-cert"), "the refusal message must name 'ca-cert'; was: " + message);
        assertTrue(message.contains("insecure-skip-tls-verify"),
                "the refusal message must name 'insecure-skip-tls-verify'; was: " + message);
        assertTrue(message.contains(url), "the refusal message must name the url; was: " + message);
    }

    @Test
    void create_withAValidCaCert_andInsecureSkipTlsVerifyExplicitlyFalse_buildsAWorkingSource(@TempDir Path dir)
            throws IOException {
        Path pem = dir.resolve("ca.crt");
        Files.writeString(pem, VALID_CA_PEM);

        CurrentVersionSource result = factory.create(
                sourceWithCaCertAndInsecureSkipTlsVerify("https://localhost:8443/current", pem.toString(), false),
                SEMVER_PARSER);

        assertInstanceOf(HttpCurrentSource.class, result,
                "ca-cert with insecure-skip-tls-verify: false must build normally, not refuse");
    }

    @Test
    void create_withInsecureSkipTlsVerifyTrue_andNoCaCert_buildsAWorkingSource() {
        CurrentVersionSource result = factory.create(
                sourceWithInsecureSkipTlsVerify("https://localhost:8443/current", true), SEMVER_PARSER);

        assertInstanceOf(HttpCurrentSource.class, result);
    }

    /**
     * A valid self-signed X.509 certificate in PEM form (generated once via
     * {@code openssl req -x509 -newkey rsa:2048 -days 36500 -nodes}). The expiry is set far in
     * the future so this fixture does not rot.
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

    // --- helpers -------------------------------------------------------------------------------

    private static ApplicationConfigLoader.VersionSource source(Optional<String> url, Optional<String> versionKey) {
        return new FakeVersionSource(url, versionKey, Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static ApplicationConfigLoader.VersionSource sourceWithAuth(String url, Auth auth) {
        return new FakeVersionSource(Optional.of(url), Optional.empty(), Optional.of(auth), Optional.empty(),
                Optional.empty());
    }

    private static ApplicationConfigLoader.VersionSource sourceWithCaCert(String url, String caCert) {
        return new FakeVersionSource(Optional.of(url), Optional.empty(), Optional.empty(), Optional.of(caCert),
                Optional.empty());
    }

    private static ApplicationConfigLoader.VersionSource sourceWithInsecureSkipTlsVerify(
            String url, boolean insecureSkipTlsVerify) {
        return new FakeVersionSource(Optional.of(url), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(insecureSkipTlsVerify));
    }

    private static ApplicationConfigLoader.VersionSource sourceWithCaCertAndInsecureSkipTlsVerify(
            String url, String caCert, boolean insecureSkipTlsVerify) {
        return new FakeVersionSource(Optional.of(url), Optional.empty(), Optional.empty(), Optional.of(caCert),
                Optional.of(insecureSkipTlsVerify));
    }

    private static Auth auth(String type, Optional<String> username, Optional<String> password) {
        return new Auth() {
            @Override
            public Optional<String> type() {
                return Optional.of(type);
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
                return Optional.empty();
            }

            @Override
            public Optional<String> tokenFile() {
                return Optional.empty();
            }
        };
    }

    /**
     * Fully implements {@link ApplicationConfigLoader.VersionSource}, defaulting every field this
     * test class does not vary to {@link Optional#empty()}. {@code type()} is fixed to
     * {@code "http-json"}.
     */
    private static final class FakeVersionSource implements ApplicationConfigLoader.VersionSource {
        private final Optional<String> url;
        private final Optional<String> versionKey;
        private final Optional<Auth> auth;
        private final Optional<String> caCert;
        private final Optional<Boolean> insecureSkipTlsVerify;

        FakeVersionSource(Optional<String> url, Optional<String> versionKey, Optional<Auth> auth,
                Optional<String> caCert, Optional<Boolean> insecureSkipTlsVerify) {
            this.url = url;
            this.versionKey = versionKey;
            this.auth = auth;
            this.caCert = caCert;
            this.insecureSkipTlsVerify = insecureSkipTlsVerify;
        }

        @Override
        public Optional<String> type() {
            return Optional.of("http-json");
        }

        @Override
        public Map<String, String> labels() {
            return Map.of();
        }

        @Override
        public Optional<String> url() {
            return url;
        }

        @Override
        public Optional<String> versionKey() {
            return versionKey;
        }

        @Override
        public Optional<Auth> auth() {
            return auth;
        }

        @Override
        public Optional<String> caCert() {
            return caCert;
        }

        @Override
        public Optional<Boolean> insecureSkipTlsVerify() {
            return insecureSkipTlsVerify;
        }

        @Override
        public Optional<String> regex() {
            return Optional.empty();
        }

        @Override
        public Optional<String> versionHeader() {
            return Optional.empty();
        }

        @Override
        public Optional<Boolean> stripPrerelease() {
            return Optional.empty();
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
        public Optional<Integer> pageSize() {
            return Optional.empty();
        }

        @Override
        public Optional<String> host() {
            return Optional.empty();
        }

        @Override
        public Optional<Integer> port() {
            return Optional.empty();
        }

        @Override
        public Optional<String> user() {
            return Optional.empty();
        }

        @Override
        public Optional<String> privateKey() {
            return Optional.empty();
        }

        @Override
        public Optional<String> privateKeyFile() {
            return Optional.empty();
        }

        @Override
        public Optional<String> hostKey() {
            return Optional.empty();
        }

        @Override
        public Optional<String> knownHosts() {
            return Optional.empty();
        }

        @Override
        public Optional<String> releaseField() {
            return Optional.empty();
        }

        @Override
        public Optional<Integer> maxTags() {
            return Optional.empty();
        }

        @Override
        public Optional<String> prereleaseFilter() {
            return Optional.empty();
        }

        @Override
        public Optional<String> registry() {
            return Optional.empty();
        }

        @Override
        public Optional<String> metric() {
            return Optional.empty();
        }

        @Override
        public Optional<String> versionLabel() {
            return Optional.empty();
        }
    }
}
