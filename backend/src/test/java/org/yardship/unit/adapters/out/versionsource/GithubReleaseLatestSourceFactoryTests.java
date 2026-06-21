package org.yardship.unit.adapters.out.versionsource;

import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionclient.ApplicationConfigLoader;
import org.yardship.adapters.out.versionsource.GithubReleaseLatestSourceFactory;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link GithubReleaseLatestSourceFactory} — the factory for the
 * {@code github-release} latest-version kind. Verifies its discriminator and its own config-fragment
 * validation (the {@code github-release} kind requires a {@code url}; {@code page-size} defaults to
 * 30 and must fail fast in {@code create()} outside GitHub's {@code per_page} range of 1–100). The
 * GitHub auth concern and REST-client construction are exercised at the integration level
 * ({@code GithubReleaseLatestSourceIT}); here we only assert the validation contract.
 *
 * <p>Note on auth: the factory OWNS the GitHub token concern, so its production constructor (the one
 * CDI uses) takes the configured token. This test constructs it via a token-free / blank-token path;
 * the implementer should keep a constructor that accepts no token (or an empty {@link Optional}) so
 * the validation contract is unit-testable without a token.
 */
class GithubReleaseLatestSourceFactoryTests {

    private final GithubReleaseLatestSourceFactory factory = new GithubReleaseLatestSourceFactory(Optional.empty());

    @Test
    void type_isGithubRelease() {
        assertEquals("github-release", factory.type());
    }

    @Test
    void create_buildsASource_whenUrlIsPresent() {
        assertNotNull(factory.create(source(Optional.of("http://localhost:8089/latest"), Optional.empty())));
    }

    @Test
    void create_rejectsAbsentUrl_withAClearMessage() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.create(source(Optional.empty(), Optional.empty())));
        assertTrue(ex.getMessage().toLowerCase().contains("url"),
                "the validation error must mention the missing 'url'; was: " + ex.getMessage());
    }

    @Test
    void create_rejectsBlankUrl_withAClearMessage() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.create(source(Optional.of("   "), Optional.empty())));
    }

    // --- page-size (issue: largest-semver-across-recent-releases) -----------------------------

    @Test
    void create_buildsASource_whenPageSizeIsAbsent_defaultingTo30() {
        // We can't observe the default directly through create()'s return value (LatestVersionSource
        // exposes only version()); this just pins that an absent page-size does not fail validation.
        // The IT (`GithubReleaseLatestSourceIT`) is responsible for observing that per_page=30 is
        // actually sent on the wire when page-size is unconfigured.
        assertNotNull(factory.create(
                source(Optional.of("http://localhost:8089/latest"), Optional.empty())));
    }

    @Test
    void create_buildsASource_whenPageSizeIsInRange() {
        assertNotNull(factory.create(
                source(Optional.of("http://localhost:8089/latest"), Optional.of(1))));
        assertNotNull(factory.create(
                source(Optional.of("http://localhost:8089/latest"), Optional.of(100))));
    }

    @Test
    void create_rejectsPageSizeBelowOne_withAClearMessage() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.create(
                        source(Optional.of("http://localhost:8089/latest"), Optional.of(0))));
        assertTrue(ex.getMessage().toLowerCase().contains("page-size")
                        || ex.getMessage().toLowerCase().contains("page size"),
                "the validation error must mention 'page-size'; was: " + ex.getMessage());
    }

    @Test
    void create_rejectsNegativePageSize_withAClearMessage() {
        assertThrows(IllegalArgumentException.class,
                () -> factory.create(
                        source(Optional.of("http://localhost:8089/latest"), Optional.of(-1))));
    }

    @Test
    void create_rejectsPageSizeAbove100_withAClearMessage() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.create(
                        source(Optional.of("http://localhost:8089/latest"), Optional.of(101))));
        assertTrue(ex.getMessage().toLowerCase().contains("page-size")
                        || ex.getMessage().toLowerCase().contains("page size"),
                "the validation error must mention 'page-size'; was: " + ex.getMessage());
    }

    private static ApplicationConfigLoader.VersionSource source(Optional<String> url) {
        return source(url, Optional.empty());
    }

    private static ApplicationConfigLoader.VersionSource source(
            Optional<String> url, Optional<Integer> pageSize) {
        return new ApplicationConfigLoader.VersionSource() {
            @Override
            public String type() {
                return "github-release";
            }

            @Override
            public Optional<String> url() {
                return url;
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
        };
    }
}
