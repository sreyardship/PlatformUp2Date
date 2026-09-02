package org.yardship.unit.adapters.out.versionsource;

import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.adapters.out.versionsource.VersionParsers;
import org.yardship.adapters.out.versionsource.current.CurrentVersionSourceFactory;
import org.yardship.adapters.out.versionsource.latest.LatestVersionSourceFactory;
import org.yardship.adapters.out.versionsource.VersionSourceResolver;
import org.yardship.core.domain.primitives.SemverVersion;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import org.yardship.core.domain.primitives.VersionValue;
import org.yardship.core.ports.out.ApplicationSources;
import org.yardship.core.ports.out.CurrentVersionSource;
import org.yardship.core.ports.out.LatestVersionSource;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link VersionSourceResolver} — the composition root that turns CDI-discovered
 * factories plus configured apps into per-app {@link ApplicationSources} pairs.
 *
 * <p><b>Test seam:</b> the production constructor injects {@code Instance<…Factory>}, the
 * {@link ApplicationConfigLoader}, and the shared {@link VersionParsers} bean — parser construction
 * lives exactly once in {@code VersionParsers}, not inline in this resolver. To
 * unit-test without a CDI container, the resolver exposes a test-visible (package-private or public)
 * constructor that accepts plain collections of factories, a plain {@code List<AppConfig>}, and a
 * {@link VersionParsers} instance (itself built via its own plain-list test constructor):
 *
 * <pre>{@code
 * VersionSourceResolver(
 *     Collection<CurrentVersionSourceFactory> currentFactories,
 *     Collection<LatestVersionSourceFactory> latestFactories,
 *     List<ApplicationConfigLoader.AppConfig> apps,
 *     VersionParsers parsers)
 * }</pre>
 *
 * <p>The resolver indexes the factories by {@code type()} and builds one source pair per app at
 * construction time — so the fail-fast paths (duplicate factory type / unknown config type) surface
 * as a constructor throw. Driven entirely by fake factories, a fake config, and a real
 * {@code VersionParsers} built from the same fake apps; no Quarkus context.
 */
class VersionSourceResolverTests {

    @Test
    void buildsOneSourcePairPerApp_byDelegatingToTheMatchingFactory() {
        FakeCurrentFactory http = new FakeCurrentFactory("http-json");
        FakeLatestFactory gh = new FakeLatestFactory("github-release");
        List<ApplicationConfigLoader.AppConfig> apps = List.of(
                app("alpha", source("http-json"), source("github-release")),
                app("beta", source("http-json"), source("github-release")));

        VersionSourceResolver resolver = new VersionSourceResolver(
                List.of(http),
                List.of(gh),
                apps,
                new VersionParsers(apps));

        List<ApplicationSources> result = resolver.applicationSources();

        assertEquals(2, result.size());
        assertEquals("alpha", result.get(0).name());
        assertEquals("beta", result.get(1).name());
        assertEquals(2, http.createCount, "the http factory builds the current source for each app");
        assertEquals(2, gh.createCount, "the github-release factory builds the latest source for each app");
    }

    @Test
    void delegatesTheExactConfigFragment_andReturnsTheFactoryProducedSource() {
        FakeCurrentFactory http = new FakeCurrentFactory("http-json");
        FakeLatestFactory gh = new FakeLatestFactory("github-release");
        ApplicationConfigLoader.VersionSource currentCfg = source("http-json");
        ApplicationConfigLoader.VersionSource latestCfg = source("github-release");
        List<ApplicationConfigLoader.AppConfig> apps = List.of(app("alpha", currentCfg, latestCfg));

        VersionSourceResolver resolver = new VersionSourceResolver(
                List.of(http), List.of(gh),
                apps, new VersionParsers(apps));

        ApplicationSources pair = resolver.applicationSources().get(0);

        assertSame(currentCfg, http.lastCfg, "the current factory receives that app's current fragment");
        assertSame(latestCfg, gh.lastCfg, "the latest factory receives that app's latest fragment");
        assertSame(http.lastProduced, pair.current(), "the resolver holds the factory-produced current source");
        assertSame(gh.lastProduced, pair.latest(), "the resolver holds the factory-produced latest source");
    }

    @Test
    void failsFast_onDuplicateCurrentFactoryType() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                new VersionSourceResolver(
                        List.of(new FakeCurrentFactory("http-json"), new FakeCurrentFactory("http-json")),
                        List.of(new FakeLatestFactory("github-release")),
                        List.of(),
                        new VersionParsers(List.of())));

        assertTrue(ex.getMessage().contains("http-json"),
                "the duplicate-type error must name the offending type; was: " + ex.getMessage());
    }

    @Test
    void failsFast_onDuplicateLatestFactoryType() {
        assertThrows(IllegalStateException.class, () ->
                new VersionSourceResolver(
                        List.of(new FakeCurrentFactory("http-json")),
                        List.of(new FakeLatestFactory("github-release"), new FakeLatestFactory("github-release")),
                        List.of(),
                        new VersionParsers(List.of())));
    }

    @Test
    void failsFast_onUnknownCurrentConfigType() {
        List<ApplicationConfigLoader.AppConfig> apps =
                List.of(app("alpha", source("mystery"), source("github-release")));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                new VersionSourceResolver(
                        List.of(new FakeCurrentFactory("http-json")),
                        List.of(new FakeLatestFactory("github-release")),
                        apps,
                        new VersionParsers(apps)));

        assertTrue(ex.getMessage().contains("mystery"),
                "the unknown-type error must name the offending config type; was: " + ex.getMessage());
    }

    @Test
    void failsFast_onUnknownLatestConfigType() {
        List<ApplicationConfigLoader.AppConfig> apps =
                List.of(app("alpha", source("http-json"), source("helm-index")));

        assertThrows(IllegalStateException.class, () ->
                new VersionSourceResolver(
                        List.of(new FakeCurrentFactory("http-json")),
                        List.of(new FakeLatestFactory("github-release")),
                        apps,
                        new VersionParsers(apps)));
    }

    // --- retired kind names (resolver-level, no-factory-found path) --------------------------

    @Test
    void failsFast_onRetiredCurrentConfigType_withAMessageNamingItsReplacement() {
        // 'http' was renamed to 'http-json'. No factory is registered for the retired name (only
        // 'http-json' is), so this must hit the no-factory-found path and be recognized as retired
        // rather than surfacing the generic unknown-type message.
        List<ApplicationConfigLoader.AppConfig> apps =
                List.of(app("alpha", source("http"), source("github-release")));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                new VersionSourceResolver(
                        List.of(new FakeCurrentFactory("http-json")),
                        List.of(new FakeLatestFactory("github-release")),
                        apps,
                        new VersionParsers(apps)));

        assertTrue(ex.getMessage().contains("'http'"),
                "the retired-kind message must name the retired type 'http'; was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("http-json"),
                "the retired-kind message must name the replacement type 'http-json'; was: "
                        + ex.getMessage());
    }

    @Test
    void failsFast_onUnknownNonRetiredCurrentConfigType_withTheExistingGenericMessage_notMentioningARename() {
        // 'does-not-exist' is neither a registered factory type nor a retired name: the resolver
        // must keep surfacing the existing generic no-factory message, not the retired-name wording.
        List<ApplicationConfigLoader.AppConfig> apps =
                List.of(app("alpha", source("does-not-exist"), source("github-release")));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                new VersionSourceResolver(
                        List.of(new FakeCurrentFactory("http-json")),
                        List.of(new FakeLatestFactory("github-release")),
                        apps,
                        new VersionParsers(apps)));

        assertTrue(ex.getMessage().contains("No version source factory for config type 'does-not-exist'"),
                "an unknown, non-retired type must keep the existing generic message; was: "
                        + ex.getMessage());
        assertTrue(!ex.getMessage().toLowerCase().contains("renam"),
                "an unknown, non-retired type must not be reported as a rename; was: " + ex.getMessage());
    }

    @Test
    void emptyAppList_yieldsNoSources() {
        VersionSourceResolver resolver = new VersionSourceResolver(
                List.of(new FakeCurrentFactory("http-json")),
                List.of(new FakeLatestFactory("github-release")),
                List.of(),
                new VersionParsers(List.of()));

        assertTrue(resolver.applicationSources().isEmpty());
    }

    // --- fakes --------------------------------------------------------------------------------

    private static ApplicationConfigLoader.AppConfig app(
            String name, ApplicationConfigLoader.VersionSource current, ApplicationConfigLoader.VersionSource latest) {
        return new ApplicationConfigLoader.AppConfig() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public ApplicationConfigLoader.VersionSource current() {
                return current;
            }

            @Override
            public ApplicationConfigLoader.VersionSource latest() {
                return latest;
            }

            @Override
            public VersionScheme versionScheme() {
                return VersionScheme.SEMVER;
            }

            @Override
            public Optional<String> calverFormat() {
                return Optional.empty();
            }

            @Override
            public Optional<String> changelogUrl() {
                return Optional.empty();
            }
        };
    }

    private static ApplicationConfigLoader.VersionSource source(String type) {
        return new ApplicationConfigLoader.VersionSource() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public Optional<String> url() {
                return Optional.of("http://localhost/" + type);
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
                return Optional.empty();
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
                return Optional.empty();
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
            public Optional<Boolean> insecureSkipTlsVerify() {
                return Optional.empty();
            }

            @Override
            public Optional<String> registry() {
                return Optional.empty();
            }
        };
    }

    private static final class FakeCurrentFactory implements CurrentVersionSourceFactory {
        private final String type;
        int createCount;
        ApplicationConfigLoader.VersionSource lastCfg;
        CurrentVersionSource lastProduced;

        FakeCurrentFactory(String type) {
            this.type = type;
        }

        @Override
        public String type() {
            return type;
        }

        @Override
        public CurrentVersionSource create(ApplicationConfigLoader.VersionSource cfg, VersionParser parser) {
            createCount++;
            lastCfg = cfg;
            lastProduced = () -> new SemverVersion("1.0.0");
            return lastProduced;
        }
    }

    private static final class FakeLatestFactory implements LatestVersionSourceFactory {
        private final String type;
        int createCount;
        ApplicationConfigLoader.VersionSource lastCfg;
        LatestVersionSource lastProduced;

        FakeLatestFactory(String type) {
            this.type = type;
        }

        @Override
        public String type() {
            return type;
        }

        @Override
        public LatestVersionSource create(ApplicationConfigLoader.VersionSource cfg, VersionParser parser) {
            createCount++;
            lastCfg = cfg;
            lastProduced = () -> new SemverVersion("2.0.0");
            return lastProduced;
        }
    }
}
