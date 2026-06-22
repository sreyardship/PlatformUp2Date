package org.yardship.unit.adapters.out.versionsource;

import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.adapters.out.versionsource.current.CurrentVersionSourceFactory;
import org.yardship.adapters.out.versionsource.latest.LatestVersionSourceFactory;
import org.yardship.adapters.out.versionsource.VersionSourceResolver;
import org.yardship.core.domain.primitives.Version;
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
 * <p><b>Test seam:</b> the production constructor injects {@code Instance<…Factory>} and the
 * {@link ApplicationConfigLoader}. To unit-test without a CDI container, the implementer must
 * provide a test-visible (package-private or public) constructor that accepts plain collections of
 * factories and a plain {@code List<AppConfig>}:
 *
 * <pre>{@code
 * VersionSourceResolver(
 *     Collection<CurrentVersionSourceFactory> currentFactories,
 *     Collection<LatestVersionSourceFactory> latestFactories,
 *     List<ApplicationConfigLoader.AppConfig> apps)
 * }</pre>
 *
 * <p>The resolver indexes the factories by {@code type()} and builds one source pair per app at
 * construction time — so the fail-fast paths (duplicate factory type / unknown config type) surface
 * as a constructor throw. Driven entirely by fake factories and a fake config; no Quarkus context.
 */
class VersionSourceResolverTests {

    @Test
    void buildsOneSourcePairPerApp_byDelegatingToTheMatchingFactory() {
        FakeCurrentFactory http = new FakeCurrentFactory("http");
        FakeLatestFactory gh = new FakeLatestFactory("github-release");

        VersionSourceResolver resolver = new VersionSourceResolver(
                List.of(http),
                List.of(gh),
                List.of(
                        app("alpha", source("http"), source("github-release")),
                        app("beta", source("http"), source("github-release"))));

        List<ApplicationSources> result = resolver.applicationSources();

        assertEquals(2, result.size());
        assertEquals("alpha", result.get(0).name());
        assertEquals("beta", result.get(1).name());
        assertEquals(2, http.createCount, "the http factory builds the current source for each app");
        assertEquals(2, gh.createCount, "the github-release factory builds the latest source for each app");
    }

    @Test
    void delegatesTheExactConfigFragment_andReturnsTheFactoryProducedSource() {
        FakeCurrentFactory http = new FakeCurrentFactory("http");
        FakeLatestFactory gh = new FakeLatestFactory("github-release");
        ApplicationConfigLoader.VersionSource currentCfg = source("http");
        ApplicationConfigLoader.VersionSource latestCfg = source("github-release");

        VersionSourceResolver resolver = new VersionSourceResolver(
                List.of(http), List.of(gh),
                List.of(app("alpha", currentCfg, latestCfg)));

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
                        List.of(new FakeCurrentFactory("http"), new FakeCurrentFactory("http")),
                        List.of(new FakeLatestFactory("github-release")),
                        List.of()));

        assertTrue(ex.getMessage().contains("http"),
                "the duplicate-type error must name the offending type; was: " + ex.getMessage());
    }

    @Test
    void failsFast_onDuplicateLatestFactoryType() {
        assertThrows(IllegalStateException.class, () ->
                new VersionSourceResolver(
                        List.of(new FakeCurrentFactory("http")),
                        List.of(new FakeLatestFactory("github-release"), new FakeLatestFactory("github-release")),
                        List.of()));
    }

    @Test
    void failsFast_onUnknownCurrentConfigType() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                new VersionSourceResolver(
                        List.of(new FakeCurrentFactory("http")),
                        List.of(new FakeLatestFactory("github-release")),
                        List.of(app("alpha", source("mystery"), source("github-release")))));

        assertTrue(ex.getMessage().contains("mystery"),
                "the unknown-type error must name the offending config type; was: " + ex.getMessage());
    }

    @Test
    void failsFast_onUnknownLatestConfigType() {
        assertThrows(IllegalStateException.class, () ->
                new VersionSourceResolver(
                        List.of(new FakeCurrentFactory("http")),
                        List.of(new FakeLatestFactory("github-release")),
                        List.of(app("alpha", source("http"), source("helm-index")))));
    }

    @Test
    void emptyAppList_yieldsNoSources() {
        VersionSourceResolver resolver = new VersionSourceResolver(
                List.of(new FakeCurrentFactory("http")),
                List.of(new FakeLatestFactory("github-release")),
                List.of());

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
            public Optional<String> caCert() {
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
        public CurrentVersionSource create(ApplicationConfigLoader.VersionSource cfg) {
            createCount++;
            lastCfg = cfg;
            lastProduced = () -> new Version("1.0.0");
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
        public LatestVersionSource create(ApplicationConfigLoader.VersionSource cfg) {
            createCount++;
            lastCfg = cfg;
            lastProduced = () -> new Version("2.0.0");
            return lastProduced;
        }
    }
}
