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
import java.util.List;
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

        assertEquals(Optional.of("test-app"), app.name());
        assertEquals(Optional.of("http-json"), app.current().type());
        assertTrue(app.current().url().isPresent(), "current.url must be read");
        assertEquals("https://example.test/version", app.current().url().get());
    }

    @Test
    void latestLeg_isTaggedGithubReleaseSourceWithRepo() {
        AppConfig app = configLoader.apps().getFirst();

        assertEquals(Optional.of("github-release"), app.latest().type());
        assertTrue(app.latest().repo().isPresent(), "latest.repo must be read");
        assertEquals("example/test-app", app.latest().repo().get());
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

    // --- issue 02 / ADR-0032: every field binds; AppConfig.name(), VersionSource.type() and ------
    // --- Auth.type() are the last non-Optional holdouts, now Optional too -----------------------
    //
    // Bound through a standalone SmallRyeConfig (like ApplicationConfigLoaderSshBindingTests and
    // changelogUrl_bindsAtAppLevel... above) rather than the shared src/test/resources/
    // application.properties, so each binding contract is pinned in isolation. Before this slice,
    // a missing name/type/auth.type failed the WHOLE @ConfigMapping at boot; these tests pin that
    // the document now binds cleanly regardless, leaving the per-app requiredness to
    // VersionSourceResolver / HttpTransportConfig (see VersionSourceResolverTests and
    // HttpTransportConfigTests for the degrade-not-throw behaviour that follows from binding).

    @Test
    void appWithNoName_bindsCleanly_asAnEmptyOptional() {
        Map<String, String> props = baseProps();
        props.put("platform-config.apps[0].current.type", "http-json");
        props.put("platform-config.apps[0].current.url", "https://example.test/version");
        props.put("platform-config.apps[0].latest.type", "github-release");
        props.put("platform-config.apps[0].latest.repo", "example/unnamed");

        AppConfig app = bind(props).apps().getFirst();

        assertTrue(app.name().isEmpty(), "an app with no 'name' configured must bind with an empty name()");
    }

    @Test
    void sourceWithNoType_bindsCleanly_asAnEmptyOptional() {
        Map<String, String> props = baseProps();
        props.put("platform-config.apps[0].name", "typeless-current");
        props.put("platform-config.apps[0].current.url", "https://example.test/version");
        props.put("platform-config.apps[0].latest.type", "github-release");
        props.put("platform-config.apps[0].latest.repo", "example/typeless-current");

        AppConfig app = bind(props).apps().getFirst();

        assertTrue(app.current().type().isEmpty(),
                "a current source with no 'type' configured must bind with an empty type()");
    }

    @Test
    void authWithNoType_bindsCleanly_asAnEmptyOptional() {
        Map<String, String> props = baseProps();
        props.put("platform-config.apps[0].name", "typeless-auth");
        props.put("platform-config.apps[0].current.type", "http-json");
        props.put("platform-config.apps[0].current.url", "https://example.test/version");
        props.put("platform-config.apps[0].current.auth.username", "harbor-bot");
        props.put("platform-config.apps[0].latest.type", "github-release");
        props.put("platform-config.apps[0].latest.repo", "example/typeless-auth");

        AppConfig app = bind(props).apps().getFirst();

        assertTrue(app.current().auth().isPresent(), "the 'auth' block itself must still bind");
        assertTrue(app.current().auth().get().type().isEmpty(),
                "an 'auth' block with no 'type' configured must bind with an empty type()");
    }

    @Test
    void aConfigWithAnUnnamedApp_aTypelessSource_andATypelessAuthBlock_allInOneDocument_bindsCleanly() {
        // Pins the acceptance criterion literally: a config containing all three defect classes at
        // once, alongside an entirely healthy sibling app, still binds as a whole document.
        Map<String, String> props = baseProps();
        props.put("platform-config.apps[0].current.type", "http-json");
        props.put("platform-config.apps[0].current.url", "https://example.test/unnamed");
        props.put("platform-config.apps[0].latest.type", "github-release");
        props.put("platform-config.apps[0].latest.repo", "example/unnamed");

        props.put("platform-config.apps[1].name", "typeless-current");
        props.put("platform-config.apps[1].current.url", "https://example.test/typeless-current");
        props.put("platform-config.apps[1].latest.type", "github-release");
        props.put("platform-config.apps[1].latest.repo", "example/typeless-current");

        props.put("platform-config.apps[2].name", "typeless-auth");
        props.put("platform-config.apps[2].current.type", "http-json");
        props.put("platform-config.apps[2].current.url", "https://example.test/typeless-auth");
        props.put("platform-config.apps[2].current.auth.username", "harbor-bot");
        props.put("platform-config.apps[2].latest.type", "github-release");
        props.put("platform-config.apps[2].latest.repo", "example/typeless-auth");

        props.put("platform-config.apps[3].name", "healthy-sibling");
        props.put("platform-config.apps[3].current.type", "http-json");
        props.put("platform-config.apps[3].current.url", "https://example.test/healthy-sibling");
        props.put("platform-config.apps[3].latest.type", "github-release");
        props.put("platform-config.apps[3].latest.repo", "example/healthy-sibling");

        List<AppConfig> apps = bind(props).apps();

        assertEquals(4, apps.size(), "the document must bind with all four app entries intact");
        assertTrue(apps.get(0).name().isEmpty());
        assertTrue(apps.get(1).current().type().isEmpty());
        assertTrue(apps.get(2).current().auth().orElseThrow().type().isEmpty());
        assertEquals(Optional.of("healthy-sibling"), apps.get(3).name());
    }

    @Test
    void auth_exposesTypeUsernamePasswordAndToken_whenPresent() {
        // Pins the nested Auth interface shape through a hand-rolled fake, the same way
        // HttpJsonCurrentSourceFactoryTests fakes VersionSource — this is the interface CONTRACT, not a
        // config-binding test (the binding-from-yaml path is covered by
        // auth_isAbsent_forAnAppConfiguredWithoutAnAuthBlock plus the dev application.yml entry).
        Auth auth = fakeAuth("basic", Optional.of("harbor-bot"), Optional.of("s3cr3t"), Optional.empty());

        assertEquals(Optional.of("basic"), auth.type());
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

        assertEquals(Optional.of("bearer"), auth.type());
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
        props.put("platform-config.apps[0].current.type", "http-json");
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
