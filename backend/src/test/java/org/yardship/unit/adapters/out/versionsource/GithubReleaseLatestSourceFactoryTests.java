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
 * validation (the {@code github-release} kind requires a {@code url}). The GitHub auth concern and
 * REST-client construction are exercised at the integration level
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
        assertNotNull(factory.create(source(Optional.of("http://localhost:8089/latest"))));
    }

    @Test
    void create_rejectsAbsentUrl_withAClearMessage() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.create(source(Optional.empty())));
        assertTrue(ex.getMessage().toLowerCase().contains("url"),
                "the validation error must mention the missing 'url'; was: " + ex.getMessage());
    }

    @Test
    void create_rejectsBlankUrl_withAClearMessage() {
        assertThrows(IllegalArgumentException.class, () -> factory.create(source(Optional.of("   "))));
    }

    private static ApplicationConfigLoader.VersionSource source(Optional<String> url) {
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
        };
    }
}
