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
        assertNotNull(factory.create(source(Optional.of("http://localhost:8089/current"))));
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
        };
    }
}
