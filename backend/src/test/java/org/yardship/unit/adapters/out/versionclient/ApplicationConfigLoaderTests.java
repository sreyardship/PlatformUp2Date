package org.yardship.unit.adapters.out.versionclient;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionclient.ApplicationConfigLoader;
import org.yardship.adapters.out.versionclient.ApplicationConfigLoader.AppConfig;
import org.yardship.adapters.out.versionclient.ApplicationConfigLoader.VersionSource.Auth;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract for the new tagged-union shape of {@code platform-config} apps (Slice 1).
 *
 * Each app's {@code current}/{@code latest} is no longer a bare string but a nested
 * interface carrying a {@code type} discriminator plus the union of optional type-specific
 * fields. This slice keeps the existing consumer working by reading {@code url} for the
 * {@code http} (current) and {@code github-release} (latest) types; the {@code namespace}/
 * {@code workload}/{@code container} fields are anticipated for Slice 3 and must be present
 * on the interface but absent (empty) for an http/github app.
 *
 * Config source is the migrated {@code src/test/resources/application.properties} default
 * test config (a single app named {@code test-app}), so no dedicated TestProfile is needed.
 */
@QuarkusTest
class ApplicationConfigLoaderTests {

    @Inject
    ApplicationConfigLoader configLoader;

    @Test
    void currentLeg_isTaggedHttpSourceWithUrl() {
        AppConfig app = configLoader.apps().getFirst();

        assertEquals("test-app", app.name());
        assertEquals("http", app.current().type());
        assertTrue(app.current().url().isPresent(), "current.url must be read");
        assertEquals("https://example.test/version", app.current().url().get());
    }

    @Test
    void latestLeg_isTaggedGithubReleaseSourceWithUrl() {
        AppConfig app = configLoader.apps().getFirst();

        assertEquals("github-release", app.latest().type());
        assertTrue(app.latest().url().isPresent(), "latest.url must be read");
        assertEquals("https://example.test/latest", app.latest().url().get());
    }

    // --- Issue 03: targeted-scrape budget config, separate and larger than scrape-trigger ------

    @Test
    void targetedScrapeTrigger_defaultsTo30PerWindow_largerThanTheFullScrapeDefaultOf10() {
        assertEquals(30, configLoader.targetedScrapeTrigger().maxPerWindow(),
                "targeted-scrape-trigger must default larger than scrape-trigger's 10/window "
                        + "so agent-driven work cannot starve the UI's full-Refresh budget");
    }

    @Test
    void targetedScrapeTrigger_defaultsWindowToOneHour() {
        assertEquals(java.time.Duration.ofHours(1), configLoader.targetedScrapeTrigger().window());
    }

    @Test
    void anticipatedKubernetesFields_areAbsentForHttpAndGithubSources() {
        AppConfig app = configLoader.apps().getFirst();

        // Slice 3 fields exist on the shape but are unset for http/github apps.
        assertFalse(app.current().namespace().isPresent());
        assertFalse(app.current().workload().isPresent());
        assertFalse(app.current().container().isPresent());
        assertFalse(app.latest().namespace().isPresent());
        assertFalse(app.latest().workload().isPresent());
        assertFalse(app.latest().container().isPresent());
    }

    // --- Issue 02: optional auth fragment on VersionSource (Harbor case study) ----------------

    @Test
    void auth_isAbsent_forAnAppConfiguredWithoutAnAuthBlock() {
        AppConfig app = configLoader.apps().getFirst();

        assertFalse(app.current().auth().isPresent(),
                "test-app has no 'auth' block configured, so current.auth() must be empty");
    }

    // Note: "an 'auth:' block without 'type' fails to bind at boot" (acceptance criterion) is NOT
    // separately tested here. 'type()' is declared without @WithDefault/Optional, which is exactly
    // how every other REQUIRED leaf on this @ConfigMapping (e.g. VersionSource.type() itself,
    // AppConfig.name()) already behaves — SmallRye throws a binding/conversion failure at startup
    // when a required leaf under a populated parent group is missing. Asserting that boot-failure
    // behaviour would require io.quarkus.test.QuarkusUnitTest (a separate classloader/test-engine
    // harness), which is not on this module's test classpath (only quarkus-junit5 is); pulling it in
    // for one test is out of scope for this slice. The implementer must declare 'Auth.type()' as a
    // bare (non-Optional) String, matching VersionSource.type() — see HttpCurrentSourceFactoryTests
    // and this class's own currentLeg_isTaggedHttpSourceWithUrl for the established required-leaf
    // pattern this relies on.
    @Test
    void auth_exposesTypeUsernamePasswordAndToken_whenPresent() {
        // Pins the shape of the new nested Auth interface via a hand-rolled fake, the same way
        // HttpCurrentSourceFactoryTests fakes VersionSource — this is the interface CONTRACT, not a
        // config-binding test (the binding-from-yaml path is covered by
        // auth_isAbsent_forAnAppConfiguredWithoutAnAuthBlock plus the dev application.yml entry).
        Auth auth = fakeAuth("basic", Optional.of("harbor-bot"), Optional.of("s3cr3t"), Optional.empty());

        assertEquals("basic", auth.type());
        assertEquals(Optional.of("harbor-bot"), auth.username());
        assertEquals(Optional.of("s3cr3t"), auth.password());
        assertEquals(Optional.empty(), auth.token());
    }

    private static Auth fakeAuth(
            String type, Optional<String> username, Optional<String> password, Optional<String> token) {
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
                return token;
            }
        };
    }
}
