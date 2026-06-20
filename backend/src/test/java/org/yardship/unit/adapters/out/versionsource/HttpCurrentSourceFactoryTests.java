package org.yardship.unit.adapters.out.versionsource;

import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionclient.ApplicationConfigLoader;
import org.yardship.adapters.out.versionsource.HttpCurrentSourceFactory;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link HttpCurrentSourceFactory} — the factory for the {@code http} current-version
 * kind. Verifies its discriminator and its own config-fragment validation (the {@code http} kind
 * requires a {@code url}). Construction of the actual REST-client-backed source is exercised at the
 * integration level ({@code HttpCurrentSourceIT}), so here we only assert the validation contract.
 */
class HttpCurrentSourceFactoryTests {

    private final HttpCurrentSourceFactory factory = new HttpCurrentSourceFactory();

    @Test
    void type_isHttp() {
        assertEquals("http", factory.type());
    }

    @Test
    void create_buildsASource_whenUrlIsPresent() {
        assertNotNull(factory.create(source(Optional.of("http://localhost:8089/current"), Optional.empty(), Optional.empty())));
    }

    @Test
    void create_rejectsAbsentUrl_withAClearMessage() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> factory.create(source(Optional.empty(), Optional.empty(), Optional.empty())));
        assertTrue(ex.getMessage().toLowerCase().contains("url"),
                "the validation error must mention the missing 'url'; was: " + ex.getMessage());
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

    private static ApplicationConfigLoader.VersionSource source(
            Optional<String> url, Optional<String> versionKey, Optional<Boolean> stripPrerelease) {
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
        };
    }
}
