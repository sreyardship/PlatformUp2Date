package org.yardship.unit.adapters.out.versionsource.latest.httpregex;

import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader.VersionSource.Auth;
import org.yardship.adapters.out.versionsource.latest.FailedLatestSource;
import org.yardship.adapters.out.versionsource.latest.HttpLatestSource;
import org.yardship.adapters.out.versionsource.latest.httpregex.HttpRegexLatestSourceFactory;
import org.yardship.core.ports.out.LatestVersionSource;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link HttpRegexLatestSourceFactory} — the factory for the {@code http-regex}
 * latest-version kind. Verifies its type discriminator, its own STRUCTURAL config-fragment
 * validation — {@code url} and {@code regex} non-blank, the regex compiling with at least one
 * capture group (the kind reads group 1); each THROWS an {@link IllegalArgumentException} — and
 * that VALUE-level {@code auth} / {@code ca-cert} / {@code insecure-skip-tls-verify} problems are
 * routed through the shared, kind-labelled {@code HttpTransportConfig} into a
 * {@link FailedLatestSource} whose message names {@code http-regex} on the latest leg, never a
 * thrown exception.
 *
 * <p>The exhaustive matrix of every individual value-error case is owned by
 * {@code HttpTransportConfigTests}; what the built transport does on the wire by
 * {@code HttpTransportIT}; the kind's own rule end to end by {@code HttpRegexLatestSourceIT}. This
 * class proves the WIRING.
 */
class HttpRegexLatestSourceFactoryTests {

    private static final VersionParser SEMVER_PARSER = new VersionParser(VersionScheme.SEMVER);

    private final HttpRegexLatestSourceFactory factory = new HttpRegexLatestSourceFactory();

    // --- type discriminator -----------------------------------------------------------------------

    @Test
    void type_isHttpRegex() {
        assertEquals("http-regex", factory.type());
    }

    // --- valid config builds a source -------------------------------------------------------------

    @Test
    void create_buildsAnHttpLatestSource_whenUrlAndRegexAreBothValid() {
        assertInstanceOf(HttpLatestSource.class, factory.create(
                source(Optional.of("http://example.com/versions"), Optional.of("Version: (\\S+)")),
                SEMVER_PARSER),
                "a non-blank url and a compilable regex with one capture group must succeed");
    }

    @Test
    void create_buildsASource_whenRegexHasMultipleCaptureGroups() {
        // The source uses group 1; additional groups are allowed (just not read).
        assertNotNull(factory.create(
                source(Optional.of("http://example.com"), Optional.of("(\\d+)\\.(\\d+)")),
                SEMVER_PARSER));
    }

    // --- url validation ---------------------------------------------------------------------------

    @Test
    void create_rejectsAbsentUrl_withAClearMessage() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.create(
                        source(Optional.empty(), Optional.of("Version: (\\S+)")),
                        SEMVER_PARSER));
        assertTrue(ex.getMessage().toLowerCase().contains("url"),
                "the validation error must mention 'url'; was: " + ex.getMessage());
    }

    @Test
    void create_rejectsBlankUrl_withAClearMessage() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.create(
                        source(Optional.of("   "), Optional.of("Version: (\\S+)")),
                        SEMVER_PARSER));
        assertTrue(ex.getMessage().toLowerCase().contains("url"),
                "the validation error must mention 'url'; was: " + ex.getMessage());
    }

    // --- regex validation -------------------------------------------------------------------------

    @Test
    void create_rejectsAbsentRegex_withAClearMessage() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.create(
                        source(Optional.of("http://example.com"), Optional.empty()),
                        SEMVER_PARSER));
        assertTrue(ex.getMessage().toLowerCase().contains("regex"),
                "the validation error must mention 'regex'; was: " + ex.getMessage());
    }

    @Test
    void create_rejectsBlankRegex_withAClearMessage() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.create(
                        source(Optional.of("http://example.com"), Optional.of("   ")),
                        SEMVER_PARSER));
        assertTrue(ex.getMessage().toLowerCase().contains("regex"),
                "the validation error must mention 'regex'; was: " + ex.getMessage());
    }

    @Test
    void create_rejectsUncompilableRegex_withAClearMessage() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.create(
                        // Unbalanced parenthesis — does not compile
                        source(Optional.of("http://example.com"), Optional.of("Version: (\\S+")),
                        SEMVER_PARSER));
        assertTrue(ex.getMessage().toLowerCase().contains("regex"),
                "the validation error must mention 'regex'; was: " + ex.getMessage());
    }

    @Test
    void create_rejectsRegexWithNoCaptureGroup_withAClearMessage() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.create(
                        // Valid pattern but zero capture groups — source cannot extract group 1
                        source(Optional.of("http://example.com"), Optional.of("Version: \\S+")),
                        SEMVER_PARSER));
        assertTrue(ex.getMessage().toLowerCase().contains("capture") ||
                   ex.getMessage().toLowerCase().contains("group") ||
                   ex.getMessage().toLowerCase().contains("regex"),
                "the validation error must explain the missing capture group; was: " + ex.getMessage());
    }

    // --- value-level: auth / ca-cert / insecure-skip-tls-verify (shared transport config) -------

    @Test
    void create_withAnInvalidAuthValue_returnsAFailedLatestSource_namingHttpRegexOnTheLatestLeg() {
        Auth unsupported = auth("oauth2", Optional.of("user"), Optional.of("pass"));

        LatestVersionSource result = factory.create(
                source(Optional.of("http://example.com"), Optional.of("(\\S+)"), Optional.of(unsupported),
                        Optional.empty(), Optional.empty()), SEMVER_PARSER);

        assertInstanceOf(FailedLatestSource.class, result);
        IllegalStateException ex = assertThrows(IllegalStateException.class, result::version);
        assertTrue(ex.getMessage().contains("The 'http-regex' latest source's"),
                "the FailedLatestSource message must name the kind and the leg; was: " + ex.getMessage());
    }

    @Test
    void create_withAValidBasicAuthValue_buildsAnHttpLatestSource() {
        Auth basic = auth("basic", Optional.of("user"), Optional.of("s3cr3t"));

        assertInstanceOf(HttpLatestSource.class, factory.create(
                source(Optional.of("http://example.com"), Optional.of("(\\S+)"), Optional.of(basic),
                        Optional.empty(), Optional.empty()), SEMVER_PARSER));
    }

    @Test
    void create_withFailedAuth_doesNotThrow_soOneBadAppCannotBlockTheOthersAtStartup() {
        Auth unsupported = auth("oauth2", Optional.empty(), Optional.empty());

        assertDoesNotThrow(() -> factory.create(
                source(Optional.of("http://example.com"), Optional.of("(\\S+)"), Optional.of(unsupported),
                        Optional.empty(), Optional.empty()), SEMVER_PARSER));
    }

    @Test
    void create_withABlankCaCert_returnsAFailedLatestSource_namingHttpRegexOnTheLatestLeg() {
        LatestVersionSource result = factory.create(
                source(Optional.of("https://example.com"), Optional.of("(\\S+)"), Optional.empty(),
                        Optional.of("   "), Optional.empty()), SEMVER_PARSER);

        assertInstanceOf(FailedLatestSource.class, result);
        IllegalStateException ex = assertThrows(IllegalStateException.class, result::version);
        assertTrue(ex.getMessage().contains("The 'http-regex' latest source's 'ca-cert'"), "was: " + ex.getMessage());
    }

    @Test
    void create_withCaCertAndInsecureSkipTlsVerifyTrue_returnsAFailedLatestSource_namingBothKeys() {
        LatestVersionSource result = factory.create(
                source(Optional.of("https://example.com"), Optional.of("(\\S+)"), Optional.empty(),
                        Optional.of("/no/such/path/ca.crt"), Optional.of(true)), SEMVER_PARSER);

        assertInstanceOf(FailedLatestSource.class, result,
                "ca-cert together with insecure-skip-tls-verify: true is ambiguous and must be refused");
        String message = assertThrows(IllegalStateException.class, result::version).getMessage();
        assertTrue(message.contains("ca-cert") && message.contains("insecure-skip-tls-verify"), "was: " + message);
    }

    @Test
    void create_checksTheStructuralFragmentFirst_soABadRegexThrowsEvenWhenAuthIsAlsoInvalid() {
        // A structural error is a ConfigError the resolver records; a value error degrades the app.
        // Structural wins: the operator fixes the regex first, then sees the auth message.
        Auth unsupported = auth("oauth2", Optional.empty(), Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> factory.create(
                source(Optional.of("http://example.com"), Optional.of("no-group"), Optional.of(unsupported),
                        Optional.empty(), Optional.empty()), SEMVER_PARSER));
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

    // --- VersionSource fake -----------------------------------------------------------------------

    /**
     * Minimal anonymous {@link ApplicationConfigLoader.VersionSource} implementation for tests.
     * All methods not relevant to {@code http-regex} return {@code Optional.empty()}.
     * The fake includes {@code Optional<String> regex()} and
     * provides it, so these tests will fail to compile until the interface has that method.
     */
    private static ApplicationConfigLoader.VersionSource source(
            Optional<String> url, Optional<String> regex) {
        return source(url, regex, Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static ApplicationConfigLoader.VersionSource source(
            Optional<String> url, Optional<String> regex, Optional<Auth> auth, Optional<String> caCert,
            Optional<Boolean> insecureSkipTlsVerify) {
        return new ApplicationConfigLoader.VersionSource() {
            @Override
            public Optional<String> type() {
                return Optional.of("http-regex");
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
            public Optional<String> regex() {
                return regex;
            }

            @Override
            public Optional<String> versionHeader() {
                return Optional.empty();
            }

            @Override
            public Optional<String> host() { return Optional.empty(); }

            @Override
            public Optional<Integer> port() { return Optional.empty(); }

            @Override
            public Optional<String> user() { return Optional.empty(); }

            @Override
            public Optional<String> privateKey() { return Optional.empty(); }

            @Override
            public Optional<String> privateKeyFile() { return Optional.empty(); }

            @Override
            public Optional<String> hostKey() { return Optional.empty(); }

            @Override
            public Optional<String> knownHosts() { return Optional.empty(); }

            @Override
            public Optional<String> releaseField() { return Optional.empty(); }

            @Override
            public Optional<String> registry() { return Optional.empty(); }

            @Override
            public Optional<String> metric() { return Optional.empty(); }

            @Override
            public Optional<String> versionLabel() { return Optional.empty(); }

            @Override
            public Optional<Integer> maxTags() { return Optional.empty(); }

            @Override
            public Optional<String> prereleaseFilter() { return Optional.empty(); }

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
                return Optional.empty();
            }

            @Override
            public Optional<Boolean> stripPrerelease() {
                return Optional.empty();
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
            public Optional<String> caCert() {
                return caCert;
            }

            @Override
            public Optional<Boolean> insecureSkipTlsVerify() {
                return insecureSkipTlsVerify;
            }

        };
    }
}
