package org.yardship.unit.adapters.out.versionsource.current.httpheader;

import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader.VersionSource.Auth;
import org.yardship.adapters.out.versionsource.current.FailedCurrentSource;
import org.yardship.adapters.out.versionsource.current.httpheader.HttpHeaderCurrentSource;
import org.yardship.adapters.out.versionsource.current.httpheader.HttpHeaderCurrentSourceFactory;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import org.yardship.core.ports.out.CurrentVersionSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link HttpHeaderCurrentSourceFactory} — the factory for the {@code http-header}
 * current-version kind (ADR-0030). Verifies its discriminator, its own STRUCTURAL config-fragment
 * validation (a non-blank {@code url} and non-blank {@code version-header} are required; a
 * configured {@code regex} must compile and have at least one capture group) — all of which THROW
 * from {@code create()} and fail boot, matching {@code url}'s precedent and
 * {@code HttpRegexLatestSourceFactory}'s {@code regex} handling — and that VALUE-level {@code auth}
 * / {@code ca-cert} problems are routed through the shared, kind-labelled
 * {@code HttpTransportConfig} collaborator into a {@link FailedCurrentSource} whose message
 * names {@code http-header} (never {@code http}), never a thrown exception.
 *
 * <p>The exhaustive matrix of every individual {@code auth}/{@code ca-cert} value-error case is
 * already owned by {@code HttpTransportConfigTests} against the shared collaborator directly;
 * this class only proves the WIRING — that this factory constructs the collaborator with the
 * {@code "http-header"} kind label and correctly maps its outcome — not every underlying rule again.
 */
class HttpHeaderCurrentSourceFactoryTests {

    private static final VersionParser SEMVER_PARSER = new VersionParser(VersionScheme.SEMVER);
    private static final String URL = "https://jenkins.example.com/";
    private static final String HEADER = "X-Jenkins";

    private final HttpHeaderCurrentSourceFactory factory = new HttpHeaderCurrentSourceFactory();

    @Test
    void type_isHttpHeader() {
        assertEquals("http-header", factory.type());
    }

    @Test
    void create_buildsAWorkingSource_forAMinimalValidFragment() {
        CurrentVersionSource result = factory.create(source(URL, HEADER, Optional.empty()), SEMVER_PARSER);

        assertInstanceOf(HttpHeaderCurrentSource.class, result);
    }

    @Test
    void create_buildsAWorkingSource_withARegexConfigured() {
        CurrentVersionSource result = factory.create(
                source(URL, HEADER, Optional.of("(\\d+\\.\\d+\\.\\d+)")), SEMVER_PARSER);

        assertInstanceOf(HttpHeaderCurrentSource.class, result);
    }

    // --- structural: url --------------------------------------------------------------------

    @Test
    void create_throws_whenUrlIsAbsent() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.create(source(Optional.empty(), Optional.of(HEADER), Optional.empty()), SEMVER_PARSER));

        assertTrue(ex.getMessage().toLowerCase().contains("url"),
                "the validation error must mention the missing 'url'; was: " + ex.getMessage());
    }

    @Test
    void create_throws_whenUrlIsBlank() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.create(source(Optional.of("   "), Optional.of(HEADER), Optional.empty()), SEMVER_PARSER));
    }

    // --- structural: version-header ---------------------------------------------------------

    @Test
    void create_throws_whenVersionHeaderIsAbsent() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.create(source(Optional.of(URL), Optional.empty(), Optional.empty()), SEMVER_PARSER));

        assertTrue(ex.getMessage().toLowerCase().contains("version-header"),
                "the validation error must mention the missing 'version-header'; was: " + ex.getMessage());
    }

    @Test
    void create_throws_whenVersionHeaderIsBlank() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.create(source(Optional.of(URL), Optional.of("   "), Optional.empty()), SEMVER_PARSER));
    }

    // --- a blank regex is treated as absent, not compiled ------------------------------------

    @Test
    void create_treatsABlankRegex_asAbsent_neitherThrowingNorBuildingAnExtractor() {
        // A blank `regex` (e.g. "   ") must be filtered to absent, exactly like every other
        // optional field this factory treats as absent-when-blank (see the factory's class
        // Javadoc). This is genuinely discriminating, not a vacuous "doesn't throw" check: a bare
        // blank pattern has ZERO capture groups, and RegexVersionExtractor's constructor rejects
        // any pattern with fewer than one capture group (see
        // create_throws_whenRegexIsConfiguredWithZeroCaptureGroups above). So if a future change
        // stopped filtering the blank value and instead compiled "   " as a real pattern, building
        // the extractor would throw right here, inside create() — this test would go red the
        // moment a blank regex started being treated as an active one. This call completing
        // without throwing is therefore proof no extractor was built from the blank pattern, not
        // merely that one was built and happened not to matter.
        CurrentVersionSource result = assertDoesNotThrow(
                () -> factory.create(source(URL, HEADER, Optional.of("   ")), SEMVER_PARSER),
                "a blank 'regex' must be treated as absent, not compiled as a real "
                        + "(zero-capture-group) pattern");

        assertInstanceOf(HttpHeaderCurrentSource.class, result);
    }

    // --- structural: regex (only when configured) -------------------------------------------

    @Test
    void create_throws_whenRegexIsConfiguredButDoesNotCompile() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.create(source(URL, HEADER, Optional.of("(unterminated")), SEMVER_PARSER));

        assertTrue(ex.getMessage().contains("regex"),
                "the validation error must mention 'regex'; was: " + ex.getMessage());
    }

    @Test
    void create_throws_whenRegexIsConfiguredWithZeroCaptureGroups() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.create(source(URL, HEADER, Optional.of("\\d+\\.\\d+\\.\\d+")), SEMVER_PARSER));
    }

    // --- value-level: auth --------------------------------------------------------------------

    @Test
    void create_withAnInvalidAuthValue_returnsAFailedCurrentSource_withAMessageNamingHttpHeader() {
        Auth basicMissingCredentials = auth("basic", Optional.empty(), Optional.empty());

        CurrentVersionSource result = factory.create(
                sourceWithAuth(URL, HEADER, basicMissingCredentials), SEMVER_PARSER);

        assertInstanceOf(FailedCurrentSource.class, result);
        IllegalStateException ex = assertThrows(IllegalStateException.class, result::version);
        assertTrue(ex.getMessage().contains("http-header"),
                "the FailedCurrentSource message must name 'http-header', not 'http'; was: " + ex.getMessage());
    }

    @Test
    void create_withAValidAuthValue_buildsAWorkingSource() {
        Auth basic = auth("basic", Optional.of("jenkins-bot"), Optional.of("s3cr3t"));

        CurrentVersionSource result = factory.create(sourceWithAuth(URL, HEADER, basic), SEMVER_PARSER);

        assertInstanceOf(HttpHeaderCurrentSource.class, result);
    }

    // --- value-level: ca-cert -------------------------------------------------------------------

    @Test
    void create_withABlankCaCert_returnsAFailedCurrentSource_withAMessageNamingHttpHeader() {
        CurrentVersionSource result = factory.create(sourceWithCaCert(URL, HEADER, "   "), SEMVER_PARSER);

        assertInstanceOf(FailedCurrentSource.class, result);
        IllegalStateException ex = assertThrows(IllegalStateException.class, result::version);
        assertTrue(ex.getMessage().contains("http-header"),
                "the FailedCurrentSource message must name 'http-header', not 'http'; was: " + ex.getMessage());
    }

    @Test
    void create_withAMissingCaCertFile_returnsAFailedCurrentSource() {
        CurrentVersionSource result = factory.create(
                sourceWithCaCert(URL, HEADER, "/no/such/path/ca.crt"), SEMVER_PARSER);

        assertInstanceOf(FailedCurrentSource.class, result);
    }

    // --- helpers -------------------------------------------------------------------------------

    private static ApplicationConfigLoader.VersionSource source(String url, String header, Optional<String> regex) {
        return source(Optional.of(url), Optional.of(header), regex);
    }

    private static ApplicationConfigLoader.VersionSource source(
            Optional<String> url, Optional<String> header, Optional<String> regex) {
        return new FakeVersionSource(url, header, regex, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
    }

    private static ApplicationConfigLoader.VersionSource sourceWithAuth(String url, String header, Auth auth) {
        return new FakeVersionSource(Optional.of(url), Optional.of(header), Optional.empty(), Optional.empty(),
                Optional.of(auth), Optional.empty(), Optional.empty());
    }

    private static ApplicationConfigLoader.VersionSource sourceWithCaCert(String url, String header, String caCert) {
        return new FakeVersionSource(Optional.of(url), Optional.of(header), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(caCert), Optional.empty());
    }

    private static Auth auth(String type, Optional<String> username, Optional<String> password) {
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
     * {@code "http-header"}.
     */
    private static final class FakeVersionSource implements ApplicationConfigLoader.VersionSource {
        private final Optional<String> url;
        private final Optional<String> versionHeader;
        private final Optional<String> regex;
        private final Optional<Boolean> stripPrerelease;
        private final Optional<Auth> auth;
        private final Optional<String> caCert;
        private final Optional<Boolean> insecureSkipTlsVerify;

        FakeVersionSource(Optional<String> url, Optional<String> versionHeader, Optional<String> regex,
                Optional<Boolean> stripPrerelease, Optional<Auth> auth, Optional<String> caCert,
                Optional<Boolean> insecureSkipTlsVerify) {
            this.url = url;
            this.versionHeader = versionHeader;
            this.regex = regex;
            this.stripPrerelease = stripPrerelease;
            this.auth = auth;
            this.caCert = caCert;
            this.insecureSkipTlsVerify = insecureSkipTlsVerify;
        }

        @Override
        public String type() {
            return "http-header";
        }

        @Override
        public Optional<String> url() {
            return url;
        }

        @Override
        public Optional<String> versionHeader() {
            return versionHeader;
        }

        @Override
        public Optional<String> regex() {
            return regex;
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
        public Optional<String> caCert() {
            return caCert;
        }

        @Override
        public Optional<Boolean> insecureSkipTlsVerify() {
            return insecureSkipTlsVerify;
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
    }
}
