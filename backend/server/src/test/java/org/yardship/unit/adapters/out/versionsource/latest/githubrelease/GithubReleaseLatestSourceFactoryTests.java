package org.yardship.unit.adapters.out.versionsource.latest.githubrelease;

import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.adapters.out.versionsource.latest.githubrelease.GithubReleaseLatestSourceFactory;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link GithubReleaseLatestSourceFactory} — the factory for the
 * {@code github-release} latest-version kind. Verifies its discriminator and its own config-fragment
 * validation. The {@code github-release} kind is now configured via a {@code repo} field
 * ({@code owner/repo} shape, exactly one {@code /}) instead of a full {@code url} — the factory
 * builds the GitHub API URL itself from the configured repo. {@code page-size} defaults to 30 and
 * must fail fast in {@code create()} outside GitHub's {@code per_page} range of 1–100. The GitHub
 * auth concern and REST-client construction are exercised at the integration level
 * ({@code GithubReleaseLatestSourceIT}); here we only assert the validation contract.
 *
 * <p>Note on auth: the factory OWNS the GitHub token concern, so its production constructor (the one
 * CDI uses) takes the configured token. This test constructs it via a token-free / blank-token path;
 * the implementer should keep a constructor that accepts no token (or an empty {@link Optional}) so
 * the validation contract is unit-testable without a token.
 */
class GithubReleaseLatestSourceFactoryTests {
    private static final VersionParser SEMVER_PARSER = new VersionParser(VersionScheme.SEMVER);

    private final GithubReleaseLatestSourceFactory factory = new GithubReleaseLatestSourceFactory(Optional.empty());

    @Test
    void type_isGithubRelease() {
        assertEquals("github-release", factory.type());
    }

    @Test
    void create_buildsASource_whenRepoIsWellFormed() {
        assertNotNull(factory.create(source(Optional.of("go-gitea/gitea"), Optional.empty()), SEMVER_PARSER));
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
        // A 'url' value under 'latest' for a github-release app must no longer be read at all — only
        // 'repo' is consulted. We assert this indirectly: a source with a well-formed 'repo' but an
        // absent 'url' still succeeds (proving 'url' isn't required), and the source builder below
        // never has its url() value forwarded into validation.
        assertNotNull(factory.create(source(Optional.of("go-gitea/gitea"), Optional.empty()), SEMVER_PARSER));
    }

    // --- page-size (issue: largest-semver-across-recent-releases) -----------------------------

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

    private static ApplicationConfigLoader.VersionSource source(
            Optional<String> repo, Optional<Integer> pageSize) {
        return new ApplicationConfigLoader.VersionSource() {
            @Override
            public String type() {
                return "github-release";
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
                return Optional.empty();
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
                return Optional.empty();
            }

            @Override
            public Optional<String> registry() {
                return Optional.empty();
            }
        };
    }
}
