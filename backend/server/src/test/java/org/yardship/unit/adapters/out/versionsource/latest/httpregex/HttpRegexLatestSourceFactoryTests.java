package org.yardship.unit.adapters.out.versionsource.latest.httpregex;

import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.adapters.out.versionsource.latest.httpregex.HttpRegexLatestSourceFactory;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link HttpRegexLatestSourceFactory} — the factory for the {@code http-regex}
 * latest-version kind. Verifies its type discriminator and its own config-fragment validation.
 *
 * <p><b>Assumed API surface:</b> {@code HttpRegexLatestSourceFactory} has a no-arg constructor
 * (or a CDI {@code @Inject} constructor that is NOT needed here since the factory itself holds no
 * external dependencies — unlike {@code GithubReleaseLatestSourceFactory} which holds a token). A
 * plain {@code new HttpRegexLatestSourceFactory()} must be sufficient for unit tests.
 *
 * <p><b>Validation contract pinned by these tests:</b>
 * <ul>
 *   <li>{@code url} must be non-blank — absent or blank throws {@link IllegalArgumentException}.</li>
 *   <li>{@code regex} must be non-blank — absent or blank throws {@link IllegalArgumentException}.</li>
 *   <li>{@code regex} must compile — an invalid pattern string throws {@link IllegalArgumentException}.</li>
 *   <li>{@code regex} must have at least one capture group — a pattern with zero groups throws
 *       {@link IllegalArgumentException} (because the source extracts group 1).</li>
 * </ul>
 * These are STRUCTURAL (boot-time) failures, consistent with {@code GithubReleaseLatestSourceFactory}'s
 * treatment of a missing/malformed {@code repo}.
 *
 * <p>The {@link ApplicationConfigLoader.VersionSource} fake below is an anonymous implementation.
 * It must implement ALL current methods of the interface — plus the new {@code regex()} method
 * added by this slice (the implementer adds {@code Optional<String> regex()} to
 * {@link ApplicationConfigLoader.VersionSource}). If the interface acquires further methods in a
 * later slice, this helper will need updating — consistent with the same pattern in
 * {@code GithubReleaseLatestSourceFactoryTests}.
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
    void create_buildsASource_whenUrlAndRegexAreBothValid() {
        assertNotNull(factory.create(
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

    // --- VersionSource fake -----------------------------------------------------------------------

    /**
     * Minimal anonymous {@link ApplicationConfigLoader.VersionSource} implementation for tests.
     * All methods not relevant to {@code http-regex} return {@code Optional.empty()}.
     * The implementer MUST add {@code Optional<String> regex()} to the interface — this fake
     * provides it, so these tests will fail to compile until the interface has that method.
     */
    private static ApplicationConfigLoader.VersionSource source(
            Optional<String> url, Optional<String> regex) {
        return new ApplicationConfigLoader.VersionSource() {
            @Override
            public String type() {
                return "http-regex";
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
                return Optional.empty();
            }

            @Override
            public Optional<Integer> pageSize() {
                return Optional.empty();
            }

            @Override
            public Optional<String> caCert() {
                return Optional.empty();
            }
            @Override
            public Optional<Boolean> insecureSkipTlsVerify() {
                return Optional.empty();
            }

        };
    }
}
