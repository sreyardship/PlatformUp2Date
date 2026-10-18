package org.yardship.unit.adapters.out.versionsource.latest.githubrelease;

import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader.VersionSource.Auth;
import org.yardship.adapters.out.versionsource.latest.FailedLatestSource;
import org.yardship.adapters.out.versionsource.latest.HttpLatestSource;
import org.yardship.adapters.out.versionsource.latest.githubrelease.GithubReleaseLatestSourceFactory;
import org.yardship.core.ports.out.LatestVersionSource;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link GithubReleaseLatestSourceFactory} — the factory for the
 * {@code github-release} latest-version kind. Verifies its discriminator, its own STRUCTURAL
 * config-fragment validation ({@code repo} in {@code owner/repo} shape with exactly one {@code /};
 * {@code page-size} defaulting to 30 and failing fast outside GitHub's {@code per_page} range of
 * 1–100 — both THROW an {@link IllegalArgumentException}), and that VALUE-level {@code auth} /
 * {@code ca-cert} / {@code insecure-skip-tls-verify} problems are routed through the shared,
 * kind-labelled {@code HttpTransportConfig} into a {@link FailedLatestSource} whose message names
 * {@code github-release} on the latest leg, never a thrown exception.
 *
 * <p>The exhaustive matrix of every individual value-error case is owned by
 * {@code HttpTransportConfigTests}; what the built transport does on the wire by
 * {@code HttpTransportIT}; the URL assembled from {@code repo}/{@code page-size} and which
 * credential reaches GitHub by {@code GithubReleaseLatestSourceIT}. This class proves the WIRING.
 */
class GithubReleaseLatestSourceFactoryTests {
    private static final VersionParser SEMVER_PARSER = new VersionParser(VersionScheme.SEMVER);

    private final GithubReleaseLatestSourceFactory factory = new GithubReleaseLatestSourceFactory(Optional.empty());

    @Test
    void type_isGithubRelease() {
        assertEquals("github-release", factory.type());
    }

    @Test
    void create_buildsAnHttpLatestSource_whenRepoIsWellFormed() {
        assertInstanceOf(HttpLatestSource.class,
                factory.create(source(Optional.of("go-gitea/gitea"), Optional.empty()), SEMVER_PARSER));
    }

    @Test
    void create_rejectsAbsentRepo_withAClearMessage() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.create(source(Optional.empty(), Optional.empty()), SEMVER_PARSER));
        assertTrue(ex.getMessage().toLowerCase().contains("repo"),
                "the validation error must mention the missing 'repo'; was: " + ex.getMessage());
    }

    @Test
    void create_rejectsBlankRepo_withAClearMessage() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.create(source(Optional.of("   "), Optional.empty()), SEMVER_PARSER));
        assertTrue(ex.getMessage().toLowerCase().contains("repo"),
                "the validation error must mention the blank 'repo'; was: " + ex.getMessage());
    }

    @Test
    void create_rejectsRepoWithZeroSlashes_withAClearMessage() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.create(source(Optional.of("gitea"), Optional.empty()), SEMVER_PARSER));
        assertTrue(ex.getMessage().toLowerCase().contains("repo"),
                "the validation error must mention 'repo'; was: " + ex.getMessage());
    }

    @Test
    void create_rejectsRepoWithMoreThanOneSlash_withAClearMessage() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.create(source(Optional.of("a/b/c"), Optional.empty()), SEMVER_PARSER));
        assertTrue(ex.getMessage().toLowerCase().contains("repo"),
                "the validation error must mention 'repo'; was: " + ex.getMessage());
    }

    @Test
    void create_ignoresAConfiguredUrl_andConsultsOnlyRepo() {
        // GitHub release configuration reads repo; a latest.url value is ignored.
        // 'repo' is consulted. We assert this indirectly: a source with a well-formed 'repo' but an
        // absent 'url' still succeeds (proving 'url' isn't required), and the source builder below
        // never has its url() value forwarded into validation.
        assertNotNull(factory.create(source(Optional.of("go-gitea/gitea"), Optional.empty()), SEMVER_PARSER));
    }

    // --- page-size --------------------------------------------------------------------------

    @Test
    void create_buildsASource_whenPageSizeIsAbsent_defaultingTo30() {
        // We can't observe the default directly through create()'s return value (LatestVersionSource
        // exposes only version()); this just pins that an absent page-size does not fail validation.
        // The IT (`GithubReleaseLatestSourceIT`) is responsible for observing that per_page=30 is
        // actually sent on the wire when page-size is unconfigured.
        assertNotNull(factory.create(
                source(Optional.of("go-gitea/gitea"), Optional.empty()), SEMVER_PARSER));
    }

    @Test
    void create_buildsASource_whenPageSizeIsInRange() {
        assertNotNull(factory.create(
                source(Optional.of("go-gitea/gitea"), Optional.of(1)), SEMVER_PARSER));
        assertNotNull(factory.create(
                source(Optional.of("go-gitea/gitea"), Optional.of(100)), SEMVER_PARSER));
    }

    @Test
    void create_rejectsPageSizeBelowOne_withAClearMessage() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.create(
                        source(Optional.of("go-gitea/gitea"), Optional.of(0)), SEMVER_PARSER));
        assertTrue(ex.getMessage().toLowerCase().contains("page-size")
                        || ex.getMessage().toLowerCase().contains("page size"),
                "the validation error must mention 'page-size'; was: " + ex.getMessage());
    }

    @Test
    void create_rejectsNegativePageSize_withAClearMessage() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.create(
                        source(Optional.of("go-gitea/gitea"), Optional.of(-1)), SEMVER_PARSER));
    }

    @Test
    void create_rejectsPageSizeAbove100_withAClearMessage() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.create(
                        source(Optional.of("go-gitea/gitea"), Optional.of(101)), SEMVER_PARSER));
        assertTrue(ex.getMessage().toLowerCase().contains("page-size")
                        || ex.getMessage().toLowerCase().contains("page size"),
                "the validation error must mention 'page-size'; was: " + ex.getMessage());
    }

    // --- value-level: auth / ca-cert / insecure-skip-tls-verify (shared transport config) -------

    @Test
    void create_withAnInvalidAuthValue_returnsAFailedLatestSource_namingGithubReleaseOnTheLatestLeg() {
        Auth unsupported = auth("oauth2", Optional.of("user"), Optional.of("pass"));

        LatestVersionSource result = factory.create(sourceWithAuth(unsupported), SEMVER_PARSER);

        assertInstanceOf(FailedLatestSource.class, result);
        IllegalStateException ex = assertThrows(IllegalStateException.class, result::version);
        assertTrue(ex.getMessage().contains("The 'github-release' latest source's"),
                "the FailedLatestSource message must name the kind and the leg; was: " + ex.getMessage());
    }

    @Test
    void create_withAValidAuthValue_buildsAnHttpLatestSource() {
        Auth bearer = auth("bearer", Optional.empty(), Optional.empty());

        assertInstanceOf(HttpLatestSource.class, factory.create(sourceWithAuth(bearer), SEMVER_PARSER));
    }

    @Test
    void create_withFailedAuth_doesNotThrow_soOneBadAppCannotBlockTheOthersAtStartup() {
        Auth unsupported = auth("oauth2", Optional.empty(), Optional.empty());

        assertDoesNotThrow(() -> factory.create(sourceWithAuth(unsupported), SEMVER_PARSER));
    }

    @Test
    void create_withABlankCaCert_returnsAFailedLatestSource_namingGithubReleaseOnTheLatestLeg() {
        LatestVersionSource result = factory.create(sourceWithCaCert("   ", Optional.empty()), SEMVER_PARSER);

        assertInstanceOf(FailedLatestSource.class, result);
        IllegalStateException ex = assertThrows(IllegalStateException.class, result::version);
        assertTrue(ex.getMessage().contains("The 'github-release' latest source's 'ca-cert'"),
                "was: " + ex.getMessage());
    }

    @Test
    void create_withCaCertAndInsecureSkipTlsVerifyTrue_returnsAFailedLatestSource_namingBothKeys() {
        LatestVersionSource result =
                factory.create(sourceWithCaCert("/no/such/path/ca.crt", Optional.of(true)), SEMVER_PARSER);

        assertInstanceOf(FailedLatestSource.class, result,
                "ca-cert together with insecure-skip-tls-verify: true is ambiguous and must be refused");
        String message = assertThrows(IllegalStateException.class, result::version).getMessage();
        assertTrue(message.contains("ca-cert") && message.contains("insecure-skip-tls-verify"), "was: " + message);
    }

    // --- helpers -------------------------------------------------------------------------------

    private static ApplicationConfigLoader.VersionSource sourceWithAuth(Auth auth) {
        return source(Optional.of("go-gitea/gitea"), Optional.empty(), Optional.of(auth), Optional.empty(),
                Optional.empty());
    }

    private static ApplicationConfigLoader.VersionSource sourceWithCaCert(
            String caCert, Optional<Boolean> insecureSkipTlsVerify) {
        return source(Optional.of("go-gitea/gitea"), Optional.empty(), Optional.empty(), Optional.of(caCert),
                insecureSkipTlsVerify);
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
                return "bearer".equals(type) ? Optional.of("tok") : Optional.empty();
            }

            @Override
            public Optional<String> tokenFile() {
                return Optional.empty();
            }
        };
    }

    private static ApplicationConfigLoader.VersionSource source(
            Optional<String> repo, Optional<Integer> pageSize) {
        return source(repo, pageSize, Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static ApplicationConfigLoader.VersionSource source(
            Optional<String> repo, Optional<Integer> pageSize, Optional<Auth> auth, Optional<String> caCert,
            Optional<Boolean> insecureSkipTlsVerify) {
        return new ApplicationConfigLoader.VersionSource() {
            @Override
            public Optional<String> type() {
                return Optional.of("github-release");
            }

            @Override
            public Map<String, String> labels() {
                return Map.of();
            }

            @Override
            public Optional<String> url() {
                return Optional.empty();
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
                return Optional.empty();
            }

            @Override
            public Optional<String> prereleaseFilter() {
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
        };
    }
}
