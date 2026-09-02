package org.yardship.unit.adapters.out.versionsource;

import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader.AppConfig;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader.VersionSource.Auth;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract for the tagged-union shape of {@code platform-config} apps.
 *
 * Each app's {@code current}/{@code latest} is a nested interface carrying a {@code type}
 * discriminator plus the union of optional type-specific fields. Fields that do not apply to an
 * HTTP or GitHub source remain absent.
 *
 * The default test configuration comes from
 * {@code src/test/resources/application.properties} (a single app named {@code test-app}), so no dedicated TestProfile is needed.
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

    // --- Targeted-scrape budget config, separate and larger than scrape-trigger ---------------

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

        // Kubernetes-specific fields are unset for HTTP and GitHub sources.
        assertFalse(app.current().namespace().isPresent());
        assertFalse(app.current().workload().isPresent());
        assertFalse(app.current().container().isPresent());
        assertFalse(app.latest().namespace().isPresent());
        assertFalse(app.latest().workload().isPresent());
        assertFalse(app.latest().container().isPresent());
    }

    // --- Optional auth fragment on VersionSource ---------------------------------------------

    @Test
    void auth_isAbsent_forAnAppConfiguredWithoutAnAuthBlock() {
        AppConfig app = configLoader.apps().getFirst();

        assertFalse(app.current().auth().isPresent(),
                "test-app has no 'auth' block configured, so current.auth() must be empty");
    }

    // An auth block without type is not tested separately here. type() is declared without
    // @WithDefault or Optional, like every other required leaf on this @ConfigMapping (e.g. VersionSource.type() itself,
    // AppConfig.name()) already behaves — SmallRye throws a binding/conversion failure at startup
    // when a required leaf under a populated parent group is missing. Asserting that boot-failure
    // behaviour would require io.quarkus.test.QuarkusUnitTest (a separate classloader/test-engine
    // harness), which is not on this module's test classpath (only quarkus-junit5 is); pulling it in
    // for this binding assertion would add a separate test engine. Auth.type() remains a bare
    // non-Optional String, matching VersionSource.type() — see HttpJsonCurrentSourceFactoryTests
    // and this class's own currentLeg_isTaggedHttpSourceWithUrl for the established required-leaf
    // pattern this relies on.
    @Test
    void auth_exposesTypeUsernamePasswordAndToken_whenPresent() {
        // Pins the nested Auth interface shape through a hand-rolled fake, the same way
        // HttpJsonCurrentSourceFactoryTests fakes VersionSource — this is the interface CONTRACT, not a
        // config-binding test (the binding-from-yaml path is covered by
        // auth_isAbsent_forAnAppConfiguredWithoutAnAuthBlock plus the dev application.yml entry).
        Auth auth = fakeAuth("basic", Optional.of("harbor-bot"), Optional.of("s3cr3t"), Optional.empty());

        assertEquals("basic", auth.type());
        assertEquals(Optional.of("harbor-bot"), auth.username());
        assertEquals(Optional.of("s3cr3t"), auth.password());
        assertEquals(Optional.empty(), auth.token());
        assertEquals(Optional.empty(), auth.tokenFile());
    }

    // --- Token-file leaf on the bearer Auth fragment ------------------------------------------

    @Test
    void auth_exposesTokenFile_whenPresent() {
        // Pins the optional token-file field on the bearer auth contract:
        // an operator may supply the token from a file (e.g. a projected K8s serviceaccount token)
        // instead of a literal/env 'token'.
        Auth auth = fakeAuth("bearer", Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of("/var/run/secrets/token"));

        assertEquals("bearer", auth.type());
        assertEquals(Optional.empty(), auth.token());
        assertEquals(Optional.of("/var/run/secrets/token"), auth.tokenFile());
    }

    private static Auth fakeAuth(
            String type, Optional<String> username, Optional<String> password, Optional<String> token) {
        return fakeAuth(type, username, password, token, Optional.empty());
    }

    private static Auth fakeAuth(
            String type, Optional<String> username, Optional<String> password, Optional<String> token,
            Optional<String> tokenFile) {
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

            @Override
            public Optional<String> tokenFile() {
                return tokenFile;
            }
        };
    }

    // --- App-level changelog-url (ADR-0021) ---------------------------------------------------

    @Test
    void changelogUrl_isEmpty_whenAbsentFromConfig() {
        // test-app (the shared test config) has no changelog-url configured.
        AppConfig app = configLoader.apps().getFirst();

        assertTrue(app.changelogUrl().isEmpty(),
                "test-app has no 'changelog-url' configured, so changelogUrl() must be empty");
    }

    @Test
    void changelogUrl_bindsAtAppLevel_siblingOfVersionScheme_whenPresent() {
        // Bound through a standalone SmallRyeConfig (like ApplicationConfigLoaderSshBindingTests)
        // rather than the shared src/test/resources/application.properties, so this test's
        // binding contract is pinned without perturbing every other test that reads 'test-app'.
        Map<String, String> props = baseProps();
        props.put("platform-config.apps[0].name", "argo-cd");
        props.put("platform-config.apps[0].current.type", "http");
        props.put("platform-config.apps[0].current.url", "https://example.test/version");
        props.put("platform-config.apps[0].latest.type", "github-release");
        props.put("platform-config.apps[0].latest.repo", "argoproj/argo-cd");
        props.put("platform-config.apps[0].changelog-url",
                "https://github.com/argoproj/argo-cd/releases/tag/v{version}");

        AppConfig app = bind(props).apps().getFirst();

        assertTrue(app.changelogUrl().isPresent(), "changelog-url must bind from config");
        assertEquals("https://github.com/argoproj/argo-cd/releases/tag/v{version}",
                app.changelogUrl().get());
    }

    private static ApplicationConfigLoader bind(Map<String, String> props) {
        SmallRyeConfig config = new SmallRyeConfigBuilder()
                .withMapping(ApplicationConfigLoader.class)
                .withSources(new PropertiesConfigSource(props, "test-changelog-url", 100))
                .build();
        return config.getConfigMapping(ApplicationConfigLoader.class);
    }

    // Common required top-level config so the standalone mapping binds (mirrors
    // ApplicationConfigLoaderSshBindingTests#baseProps — durations in ISO-8601 since this
    // standalone SmallRyeConfig does not register Quarkus's "1h" shorthand converter).
    private static Map<String, String> baseProps() {
        Map<String, String> props = new HashMap<>();
        props.put("platform-config.scrape-interval", "1h");
        props.put("platform-config.scrape-trigger.max-per-window", "10");
        props.put("platform-config.scrape-trigger.window", "PT1H");
        props.put("platform-config.targeted-scrape-trigger.max-per-window", "30");
        props.put("platform-config.targeted-scrape-trigger.window", "PT1H");
        return props;
    }
}
