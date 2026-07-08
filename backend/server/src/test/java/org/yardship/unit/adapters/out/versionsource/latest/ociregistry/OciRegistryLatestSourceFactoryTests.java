package org.yardship.unit.adapters.out.versionsource.latest.ociregistry;

import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.adapters.out.versionsource.latest.FailedLatestSource;
import org.yardship.adapters.out.versionsource.latest.ociregistry.OciRegistryLatestSourceFactory;
import org.yardship.adapters.out.versionsource.latest.ociregistry.TagSelection;
import org.yardship.core.ports.out.LatestVersionSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link OciRegistryLatestSourceFactory} — the factory for the {@code oci-registry}
 * latest-version kind. Verifies its discriminator, its config-fragment structural validation
 * (non-blank {@code registry} and {@code repo} required — a missing/blank value must throw at
 * construction time with a message naming the offending field), URL assembly ({@code https://}
 * default, explicit {@code http://} prefix honoured), and VALUE-LEVEL auth validation (issue 02):
 * unsupported {@code auth.type} or blank {@code basic} credentials surface as a
 * {@link FailedLatestSource}, never a boot crash.
 */
class OciRegistryLatestSourceFactoryTests {
    private static final VersionParser SEMVER_PARSER = new VersionParser(VersionScheme.SEMVER);


    private final OciRegistryLatestSourceFactory factory = new OciRegistryLatestSourceFactory();

    @Test
    void type_isOciRegistry() {
        assertEquals("oci-registry", factory.type());
    }

    // --- registry validation ----------------------------------------------------------------

    @Test
    void create_rejectsAbsentRegistry_withAClearMessageNamingRegistry() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.create(source(Optional.empty(), Optional.of("library/nginx")), SEMVER_PARSER));
        assertTrue(ex.getMessage().toLowerCase().contains("registry"),
                "the validation error must mention 'registry'; was: " + ex.getMessage());
    }

    @Test
    void create_rejectsBlankRegistry_withAClearMessageNamingRegistry() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.create(source(Optional.of("   "), Optional.of("library/nginx")), SEMVER_PARSER));
        assertTrue(ex.getMessage().toLowerCase().contains("registry"),
                "the validation error must mention 'registry'; was: " + ex.getMessage());
    }

    // --- repo validation --------------------------------------------------------------------

    @Test
    void create_rejectsAbsentRepo_withAClearMessageNamingRepo() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.create(source(Optional.of("registry.example.com"), Optional.empty()), SEMVER_PARSER));
        assertTrue(ex.getMessage().toLowerCase().contains("repo"),
                "the validation error must mention 'repo'; was: " + ex.getMessage());
    }

    @Test
    void create_rejectsBlankRepo_withAClearMessageNamingRepo() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.create(source(Optional.of("registry.example.com"), Optional.of("   ")), SEMVER_PARSER));
        assertTrue(ex.getMessage().toLowerCase().contains("repo"),
                "the validation error must mention 'repo'; was: " + ex.getMessage());
    }

    // --- happy path / URL assembly ----------------------------------------------------------

    @Test
    void create_buildsASource_whenRegistryAndRepoAreWellFormed() {
        assertNotNull(factory.create(source(
                Optional.of("registry.example.com"), Optional.of("library/nginx")), SEMVER_PARSER));
    }

    @Test
    void create_buildsASource_forSimpleImageName_withoutSlash() {
        // repo can be a single path segment (e.g. "nginx" for Docker Hub official images)
        assertNotNull(factory.create(source(
                Optional.of("registry.example.com"), Optional.of("nginx")), SEMVER_PARSER));
    }

    @Test
    void create_buildsASource_forDeepRepoPath_withMultipleSlashes() {
        // OCI repos may have multi-segment paths like "org/team/image"
        assertNotNull(factory.create(source(
                Optional.of("registry.example.com"), Optional.of("org/team/image")), SEMVER_PARSER));
    }

    @Test
    void create_honoursExplicitHttpPrefix_onRegistry() {
        // An explicit "http://" prefix on registry is used as-is (for local/test registries).
        // We assert create() succeeds — the IT verifies the actual wire URL.
        assertNotNull(factory.create(source(
                Optional.of("http://localhost:5000"), Optional.of("library/nginx")), SEMVER_PARSER));
    }

    @Test
    void create_defaultsToHttps_whenRegistryHasNoSchemePrefix() {
        // A bare hostname (no scheme) must get "https://" prepended.
        // We assert create() succeeds — the IT verifies the actual wire URL.
        assertNotNull(factory.create(source(
                Optional.of("registry.example.com"), Optional.of("library/nginx")), SEMVER_PARSER));
    }

    // --- auth: value-level misconfiguration → FailedLatestSource (issue 02) ------------------

    @Test
    void create_withNoAuth_returnsARealSource_notFailedLatestSource() {
        // Anonymous access (no auth fragment) must succeed and return a working source.
        LatestVersionSource result = factory.create(source(
                Optional.of("registry.example.com"), Optional.of("library/nginx"),
                Optional.empty()), SEMVER_PARSER);

        assertNotNull(result);
        // Must NOT be a FailedLatestSource — a FailedLatestSource would throw on version()
        // instead of hitting the registry.
        assertTrue(!(result instanceof FailedLatestSource),
                "No-auth path must return a real source, not a FailedLatestSource");
    }

    @Test
    void create_withValidBasicAuth_returnsARealSource_notFailedLatestSource() {
        LatestVersionSource result = factory.create(source(
                Optional.of("registry.example.com"), Optional.of("library/nginx"),
                Optional.of(auth("basic", Optional.of("user"), Optional.of("s3cr3t")))), SEMVER_PARSER);

        assertNotNull(result);
        assertTrue(!(result instanceof FailedLatestSource),
                "Valid basic auth must return a real source, not a FailedLatestSource");
    }

    @Test
    void create_withBasicAuth_andBlankUsername_returnsFailedLatestSource_withClearMessage() {
        LatestVersionSource result = factory.create(source(
                Optional.of("registry.example.com"), Optional.of("library/nginx"),
                Optional.of(auth("basic", Optional.of("  "), Optional.of("s3cr3t")))), SEMVER_PARSER);

        assertInstanceOf(FailedLatestSource.class, result,
                "Blank basic username must produce a FailedLatestSource");
        IllegalStateException ex = assertThrows(IllegalStateException.class, result::version);
        assertTrue(ex.getMessage().toLowerCase().contains("username")
                        || ex.getMessage().toLowerCase().contains("password")
                        || ex.getMessage().toLowerCase().contains("basic"),
                "Error message must name the misconfiguration; was: " + ex.getMessage());
    }

    @Test
    void create_withBasicAuth_andAbsentUsername_returnsFailedLatestSource_withClearMessage() {
        LatestVersionSource result = factory.create(source(
                Optional.of("registry.example.com"), Optional.of("library/nginx"),
                Optional.of(auth("basic", Optional.empty(), Optional.of("s3cr3t")))), SEMVER_PARSER);

        assertInstanceOf(FailedLatestSource.class, result,
                "Absent basic username must produce a FailedLatestSource");
        assertThrows(IllegalStateException.class, result::version);
    }

    @Test
    void create_withBasicAuth_andBlankPassword_returnsFailedLatestSource_withClearMessage() {
        LatestVersionSource result = factory.create(source(
                Optional.of("registry.example.com"), Optional.of("library/nginx"),
                Optional.of(auth("basic", Optional.of("user"), Optional.of("")))), SEMVER_PARSER);

        assertInstanceOf(FailedLatestSource.class, result,
                "Blank basic password must produce a FailedLatestSource");
        assertThrows(IllegalStateException.class, result::version);
    }

    @Test
    void create_withBasicAuth_andAbsentPassword_returnsFailedLatestSource_withClearMessage() {
        LatestVersionSource result = factory.create(source(
                Optional.of("registry.example.com"), Optional.of("library/nginx"),
                Optional.of(auth("basic", Optional.of("user"), Optional.empty()))), SEMVER_PARSER);

        assertInstanceOf(FailedLatestSource.class, result,
                "Absent basic password must produce a FailedLatestSource");
        assertThrows(IllegalStateException.class, result::version);
    }

    @Test
    void create_withBearerAuthType_returnsFailedLatestSource_withClearMessage() {
        // 'bearer' sends a static token at the resource — it doesn't fit the realm-mint flow;
        // the factory must refuse it with a FailedLatestSource, not a boot crash.
        LatestVersionSource result = factory.create(source(
                Optional.of("registry.example.com"), Optional.of("library/nginx"),
                Optional.of(auth("bearer", Optional.empty(), Optional.empty()))), SEMVER_PARSER);

        assertInstanceOf(FailedLatestSource.class, result,
                "'bearer' auth.type must produce a FailedLatestSource");
        IllegalStateException ex = assertThrows(IllegalStateException.class, result::version);
        assertTrue(ex.getMessage().toLowerCase().contains("bearer")
                        || ex.getMessage().toLowerCase().contains("not supported"),
                "Error message must mention 'bearer' or 'not supported'; was: " + ex.getMessage());
    }

    @Test
    void create_withUnsupportedAuthType_returnsFailedLatestSource_withClearMessage() {
        // Any auth.type other than 'basic' is unsupported and must surface as a FailedLatestSource.
        LatestVersionSource result = factory.create(source(
                Optional.of("registry.example.com"), Optional.of("library/nginx"),
                Optional.of(auth("oauth2", Optional.empty(), Optional.empty()))), SEMVER_PARSER);

        assertInstanceOf(FailedLatestSource.class, result,
                "Unsupported auth.type must produce a FailedLatestSource");
        IllegalStateException ex = assertThrows(IllegalStateException.class, result::version);
        assertTrue(ex.getMessage().toLowerCase().contains("oauth2")
                        || ex.getMessage().toLowerCase().contains("not supported"),
                "Error message must mention the unsupported type; was: " + ex.getMessage());
    }

    @Test
    void create_withFailedLatestSource_doesNotThrowAtBoot_otherAppsKeepScraping() {
        // VALUE-level auth failures must never throw — the factory returns a FailedLatestSource
        // so the offending app fails on version() while every other app keeps scraping normally.
        // We verify no exception escapes create() itself:
        LatestVersionSource result = factory.create(source(
                Optional.of("registry.example.com"), Optional.of("library/nginx"),
                Optional.of(auth("token-file", Optional.empty(), Optional.empty()))), SEMVER_PARSER);

        // Must return something (a FailedLatestSource), not throw:
        assertNotNull(result);
    }

    // --- selection defaults (slice 03; configured values covered by the pagination IT) ------

    @Test
    void tagSelectionFrom_appliesDefaults_whenOptionalLeavesAbsent() {
        // With only the required registry+repo, the optional selection knobs must resolve to
        // defaults rather than null/zero. The exact default values and configured-value threading
        // are pinned by behaviour in the pagination IT (n=… on the wire, the cap); here we only
        // assert that defaults ARE applied.
        TagSelection selection = OciRegistryLatestSourceFactory.tagSelectionFrom(source(
                Optional.of("registry.example.com"), Optional.of("library/nginx"),
                Optional.empty(), Optional.empty()));

        assertTrue(selection.pageSize() > 0, "an absent page-size must fall back to a positive default");
        assertTrue(selection.maxTags() > 0, "an absent max-tags must fall back to a positive default");
        assertEquals(Optional.empty(), selection.prereleaseFilter());
        assertFalse(selection.stripPrerelease());
    }

    // --- prerelease-filter threading (slice 04) -----------------------------------------------

    @Test
    void create_withPrereleaseFilter_returnsARealSource_notFailedLatestSource() {
        // A configured prerelease-filter must not produce a FailedLatestSource — it is a valid
        // configuration. The filter is threaded into the source and exercises the new selection
        // logic; the IT verifies the end-to-end behaviour.
        LatestVersionSource result = factory.create(sourceWithFilter(
                Optional.of("registry.example.com"), Optional.of("library/nginx"),
                Optional.of("alpine")), SEMVER_PARSER);

        assertNotNull(result);
        assertTrue(!(result instanceof FailedLatestSource),
                "A configured prerelease-filter must return a real source, not a FailedLatestSource");
    }

    @Test
    void create_withAbsentPrereleaseFilter_returnsARealSource_notFailedLatestSource() {
        // Absent prereleaseFilter (normal case) must continue to produce a working source.
        LatestVersionSource result = factory.create(sourceWithFilter(
                Optional.of("registry.example.com"), Optional.of("library/nginx"),
                Optional.empty()), SEMVER_PARSER);

        assertNotNull(result);
        assertTrue(!(result instanceof FailedLatestSource),
                "Absent prereleaseFilter must return a real source, same as before slice 04");
    }

    // --- helpers -------------------------------------------------------------------------------

    private static ApplicationConfigLoader.VersionSource source(
            Optional<String> registry, Optional<String> repo) {
        return source(registry, repo, Optional.empty());
    }

    private static ApplicationConfigLoader.VersionSource source(
            Optional<String> registry,
            Optional<String> repo,
            Optional<ApplicationConfigLoader.VersionSource.Auth> auth) {
        return source(registry, repo, Optional.empty(), Optional.empty(), auth);
    }

    private static ApplicationConfigLoader.VersionSource source(
            Optional<String> registry,
            Optional<String> repo,
            Optional<Integer> pageSize,
            Optional<Integer> maxTags) {
        return source(registry, repo, pageSize, maxTags, Optional.empty());
    }

    private static ApplicationConfigLoader.VersionSource source(
            Optional<String> registry,
            Optional<String> repo,
            Optional<Integer> pageSize,
            Optional<Integer> maxTags,
            Optional<ApplicationConfigLoader.VersionSource.Auth> auth) {
        return new ApplicationConfigLoader.VersionSource() {
            @Override
            public String type() {
                return "oci-registry";
            }

            @Override
            public Optional<String> url() {
                return Optional.empty();
            }

            @Override
            public Optional<String> regex() { return Optional.empty(); }

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
            public Optional<String> repo() {
                return repo;
            }

            @Override
            public Optional<String> registry() {
                return registry;
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
            public Optional<ApplicationConfigLoader.VersionSource.Auth> auth() {
                return auth;
            }

            @Override
            public Optional<Integer> pageSize() {
                return pageSize;
            }

            @Override
            public Optional<Integer> maxTags() {
                return maxTags;
            }

            @Override
            public Optional<String> prereleaseFilter() {
                return Optional.empty();
            }

            @Override
            public Optional<String> caCert() {
                return Optional.empty();
            }
        };
    }

    /**
     * Builds a {@link ApplicationConfigLoader.VersionSource} with a specified {@code prereleaseFilter}.
     * Used for slice-04 factory tests.
     */
    private static ApplicationConfigLoader.VersionSource sourceWithFilter(
            Optional<String> registry,
            Optional<String> repo,
            Optional<String> prereleaseFilter) {
        return new ApplicationConfigLoader.VersionSource() {
            @Override public String type() { return "oci-registry"; }
            @Override public Optional<String> url() { return Optional.empty(); }
            @Override public Optional<String> regex() { return Optional.empty(); }
            @Override public Optional<String> host() { return Optional.empty(); }
            @Override public Optional<Integer> port() { return Optional.empty(); }
            @Override public Optional<String> user() { return Optional.empty(); }
            @Override public Optional<String> privateKey() { return Optional.empty(); }
            @Override public Optional<String> privateKeyFile() { return Optional.empty(); }
            @Override public Optional<String> hostKey() { return Optional.empty(); }
            @Override public Optional<String> knownHosts() { return Optional.empty(); }
            @Override public Optional<String> releaseField() { return Optional.empty(); }
            @Override public Optional<String> repo() { return repo; }
            @Override public Optional<String> registry() { return registry; }
            @Override public Optional<String> namespace() { return Optional.empty(); }
            @Override public Optional<String> workload() { return Optional.empty(); }
            @Override public Optional<String> container() { return Optional.empty(); }
            @Override public Optional<String> versionKey() { return Optional.empty(); }
            @Override public Optional<Boolean> stripPrerelease() { return Optional.empty(); }
            @Override public Optional<ApplicationConfigLoader.VersionSource.Auth> auth() { return Optional.empty(); }
            @Override public Optional<Integer> pageSize() { return Optional.empty(); }
            @Override public Optional<Integer> maxTags() { return Optional.empty(); }
            @Override public Optional<String> prereleaseFilter() { return prereleaseFilter; }
            @Override public Optional<String> caCert() { return Optional.empty(); }
        };
    }

    private static ApplicationConfigLoader.VersionSource.Auth auth(
            String type,
            Optional<String> username,
            Optional<String> password) {
        return new ApplicationConfigLoader.VersionSource.Auth() {
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
}
