package org.yardship.unit.adapters.out.versionsource;

import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.adapters.out.versionsource.VersionParsers;
import org.yardship.adapters.out.versionsource.configerror.ConfigError;
import org.yardship.adapters.out.versionsource.configerror.ConfigErrorScope;
import org.yardship.adapters.out.versionsource.current.CurrentVersionSourceFactory;
import org.yardship.adapters.out.versionsource.current.FailedCurrentSource;
import org.yardship.adapters.out.versionsource.latest.FailedLatestSource;
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
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * construction time — so the one remaining fail-fast path, a duplicate factory {@code type()},
 * surfaces as a constructor throw. Driven entirely by fake factories, a fake config, and a real
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

    // NOTE (ADR-0032 / issue 01): an unknown or retired config `type` used to fail the boot outright
    // with a constructor-time IllegalStateException. Both now degrade the affected side instead —
    // see unknownConfigType_degradesThatSide_... and retiredConfigType_degradesThatSide_... below,
    // which cover the exact same message wording this way. The former failsFast_on*ConfigType tests
    // that asserted a constructor-time throw for these cases have been superseded and removed
    // accordingly.

    @Test
    void emptyAppList_yieldsNoSources() {
        VersionSourceResolver resolver = new VersionSourceResolver(
                List.of(new FakeCurrentFactory("http-json")),
                List.of(new FakeLatestFactory("github-release")),
                List.of(),
                new VersionParsers(List.of()));

        assertTrue(resolver.applicationSources().isEmpty());
    }

    @Test
    void cleanConfig_recordsNoConfigErrors_andLogsNothingAtSevereOrWarning() {
        FakeCurrentFactory http = new FakeCurrentFactory("http-json");
        FakeLatestFactory gh = new FakeLatestFactory("github-release");
        List<ApplicationConfigLoader.AppConfig> apps = List.of(
                app("alpha", source("http-json"), source("github-release")),
                app("beta", source("http-json"), source("github-release")));

        try (TestLogHandler logs = new TestLogHandler(VersionSourceResolver.class.getName())) {
            VersionSourceResolver resolver = new VersionSourceResolver(
                    List.of(http), List.of(gh), apps, new VersionParsers(apps));

            assertTrue(resolver.configErrors().isEmpty(),
                    "a fully valid two-app config must record no config errors");
            assertTrue(logs.recordsAtLevel(Level.SEVERE).isEmpty(), "a clean config must log nothing at SEVERE");
            assertTrue(logs.recordsAtLevel(Level.WARNING).isEmpty(), "a clean config must log nothing at WARNING");
        }
    }

    // --- config errors are recorded, not thrown (issue 01 / ADR-0032) -------------------------
    //
    // These tests describe the resolver's contract: a throwing factory (or an unknown/retired
    // `type`) never escapes the resolver's constructor. Instead the affected side degrades to a
    // Failed*Source carrying the same message, and the resolver — a ConfigErrorSource — records one
    // ConfigError of the matching scope. A duplicate factory type() is the one case that still
    // throws (covered above, unchanged).

    @Test
    void throwingCurrentFactory_degradesOnlyTheCurrentSide_andRecordsOneCurrentScopeError() {
        FakeCurrentFactory http = FakeCurrentFactory.throwing("http-json", new IllegalArgumentException(
                "The 'http-json' current source's 'url' must not be blank."));
        FakeLatestFactory gh = new FakeLatestFactory("github-release");
        List<ApplicationConfigLoader.AppConfig> apps =
                List.of(app("alpha", source("http-json"), source("github-release")));

        VersionSourceResolver resolver = new VersionSourceResolver(
                List.of(http), List.of(gh), apps, new VersionParsers(apps));

        ApplicationSources pair = resolver.applicationSources().get(0);
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> pair.current().version());
        assertEquals("The 'http-json' current source's 'url' must not be blank.", thrown.getMessage(),
                "the degraded current side must re-throw the factory's original message on version()");

        assertEquals(new SemverVersion("2.0.0"), pair.latest().version(),
                "the OTHER side of the same app must still build and read normally");
        assertEquals(1, gh.createCount, "the latest factory must still have been called for this app");

        assertEquals(
                List.of(new ConfigError("alpha", ConfigErrorScope.CURRENT,
                        "The 'http-json' current source's 'url' must not be blank.")),
                resolver.configErrors());
    }

    @Test
    void throwingLatestFactory_degradesOnlyTheLatestSide_andRecordsOneLatestScopeError() {
        FakeCurrentFactory http = new FakeCurrentFactory("http-json");
        FakeLatestFactory gh = FakeLatestFactory.throwing("github-release",
                new IllegalArgumentException("The 'github-release' latest source's 'repo' must not be blank."));
        List<ApplicationConfigLoader.AppConfig> apps =
                List.of(app("alpha", source("http-json"), source("github-release")));

        VersionSourceResolver resolver = new VersionSourceResolver(
                List.of(http), List.of(gh), apps, new VersionParsers(apps));

        ApplicationSources pair = resolver.applicationSources().get(0);
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> pair.latest().version());
        assertEquals("The 'github-release' latest source's 'repo' must not be blank.", thrown.getMessage());

        assertEquals(1, http.createCount, "the OTHER (current) side must still have been built");

        assertEquals(
                List.of(new ConfigError("alpha", ConfigErrorScope.LATEST,
                        "The 'github-release' latest source's 'repo' must not be blank.")),
                resolver.configErrors());
    }

    @Test
    void aSiblingApp_isEntirelyUnaffectedByAnotherApps_throwingFactory() {
        // Apps are resolved in config order (see the very first test in this class), so a factory
        // that throws only on its first call models exactly one app's factory-rejected fragment,
        // leaving the sibling app — same source type, same factory bean — to build normally.
        FakeCurrentFactory http =
                FakeCurrentFactory.throwingOnce("http-json", new IllegalArgumentException("broken"));
        FakeLatestFactory gh = new FakeLatestFactory("github-release");
        List<ApplicationConfigLoader.AppConfig> apps = List.of(
                app("broken-app", source("http-json"), source("github-release")),
                app("healthy-app", source("http-json"), source("github-release")));

        VersionSourceResolver resolver = new VersionSourceResolver(
                List.of(http), List.of(gh), apps, new VersionParsers(apps));

        ApplicationSources broken = resolver.applicationSources().get(0);
        ApplicationSources healthy = resolver.applicationSources().get(1);
        assertEquals("healthy-app", healthy.name());
        assertThrows(IllegalStateException.class, () -> broken.current().version(),
                "the first app's current side must be degraded");
        assertEquals(new SemverVersion("1.0.0"), healthy.current().version(),
                "the sibling app must be entirely unaffected: its current side builds and reads normally");
        assertEquals(new SemverVersion("2.0.0"), healthy.latest().version());

        assertEquals(
                List.of(new ConfigError("broken-app", ConfigErrorScope.CURRENT, "broken")),
                resolver.configErrors(),
                "only the broken app's own defect is recorded; the healthy sibling contributes none");
    }

    @Test
    void unknownConfigType_degradesThatSide_withAMessageNamingTheUnknownKind_ratherThanFailingBoot() {
        List<ApplicationConfigLoader.AppConfig> apps =
                List.of(app("alpha", source("mystery"), source("github-release")));

        VersionSourceResolver resolver = new VersionSourceResolver(
                List.of(new FakeCurrentFactory("http-json")),
                List.of(new FakeLatestFactory("github-release")),
                apps,
                new VersionParsers(apps));

        ApplicationSources pair = resolver.applicationSources().get(0);
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> pair.current().version());
        assertTrue(thrown.getMessage().contains("mystery"),
                "the degraded source's message must name the unknown type; was: " + thrown.getMessage());

        assertEquals(1, resolver.configErrors().size());
        ConfigError error = resolver.configErrors().get(0);
        assertEquals("alpha", error.application());
        assertEquals(ConfigErrorScope.CURRENT, error.scope());
        assertTrue(error.reason().contains("mystery"));
    }

    @Test
    void retiredConfigType_degradesThatSide_withTheExistingRenameHint_ratherThanFailingBoot() {
        // 'http' was renamed to 'http-json'; no factory is registered for 'http' itself.
        List<ApplicationConfigLoader.AppConfig> apps =
                List.of(app("alpha", source("http"), source("github-release")));

        VersionSourceResolver resolver = new VersionSourceResolver(
                List.of(new FakeCurrentFactory("http-json")),
                List.of(new FakeLatestFactory("github-release")),
                apps,
                new VersionParsers(apps));

        ApplicationSources pair = resolver.applicationSources().get(0);
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> pair.current().version());
        assertTrue(thrown.getMessage().contains("'http'") && thrown.getMessage().contains("http-json"),
                "the degraded source's message must keep the rename hint; was: " + thrown.getMessage());

        assertEquals(1, resolver.configErrors().size());
        ConfigError error = resolver.configErrors().get(0);
        assertEquals(ConfigErrorScope.CURRENT, error.scope());
        assertTrue(error.reason().contains("http-json"));
    }

    @Test
    void duplicateFactoryType_stillThrowsAtConstruction_ratherThanDegrading() {
        // Unchanged behaviour, re-asserted here for the reader: our own beans claiming one type is a
        // defect no per-app degradation is definable for — it still fails boot outright.
        assertThrows(IllegalStateException.class, () ->
                new VersionSourceResolver(
                        List.of(new FakeCurrentFactory("http-json"), new FakeCurrentFactory("http-json")),
                        List.of(new FakeLatestFactory("github-release")),
                        List.of(),
                        new VersionParsers(List.of())));
    }

    @Test
    void undeclaredExceptionFromAFactory_isRecordedAsAConfigError_andLoggedAtError_notWarn() {
        // IllegalArgumentException is a factory's DECLARED config-validation contract (see e.g.
        // HttpJsonCurrentSourceFactory / GithubReleaseLatestSourceFactory). Anything else — an NPE
        // here standing in for a defect in OUR code, not the operator's config — must still degrade
        // the side (the "one app can never take the fleet down" promise holds absolutely) but must
        // ALSO be logged at ERROR by the resolver itself, so our own bugs stay visible as bugs
        // instead of blending into ordinary operator-facing config warnings.
        FakeCurrentFactory http = FakeCurrentFactory.throwing("http-json", new NullPointerException("npe boom"));
        FakeLatestFactory gh = new FakeLatestFactory("github-release");
        List<ApplicationConfigLoader.AppConfig> apps =
                List.of(app("alpha", source("http-json"), source("github-release")));

        try (TestLogHandler logs = new TestLogHandler(VersionSourceResolver.class.getName())) {
            VersionSourceResolver resolver = new VersionSourceResolver(
                    List.of(http), List.of(gh), apps, new VersionParsers(apps));

            assertEquals(1, resolver.configErrors().size());
            assertEquals(ConfigErrorScope.CURRENT, resolver.configErrors().get(0).scope());
            assertEquals("alpha", resolver.configErrors().get(0).application());

            assertFalse(logs.recordsAtLevel(Level.SEVERE).isEmpty(),
                    "an undeclared RuntimeException must be logged at ERROR (java.util.logging SEVERE)");
            assertTrue(logs.recordsAtLevel(Level.WARNING).isEmpty(),
                    "an undeclared RuntimeException must not ALSO be separately logged at WARN by the "
                            + "resolver — the aggregate boot WARN is the only WARN, emitted later by "
                            + "ConfigErrorBootReporter");
        }
    }

    @Test
    void declaredConfigValidationException_isRecordedButNotLoggedAtErrorByTheResolverItself() {
        // The mirror image of the test above: a factory's own declared IllegalArgumentException is
        // an operator config error, not our own defect, so the resolver itself must not log it at
        // ERROR (it is still recorded, and still reported later — once — by ConfigErrorBootReporter).
        FakeCurrentFactory http = FakeCurrentFactory.throwing("http-json", new IllegalArgumentException("blank url"));
        FakeLatestFactory gh = new FakeLatestFactory("github-release");
        List<ApplicationConfigLoader.AppConfig> apps =
                List.of(app("alpha", source("http-json"), source("github-release")));

        try (TestLogHandler logs = new TestLogHandler(VersionSourceResolver.class.getName())) {
            new VersionSourceResolver(List.of(http), List.of(gh), apps, new VersionParsers(apps));

            assertTrue(logs.recordsAtLevel(Level.SEVERE).isEmpty(),
                    "a factory's own declared IllegalArgumentException must not be logged at ERROR by "
                            + "the resolver; was: " + logs.recordsAtLevel(Level.SEVERE));
        }
    }

    // --- a factory that RETURNS a Failed*Source itself, rather than throwing -------------------
    //
    // Several factories (HttpJsonCurrentSourceFactory, HttpHeaderCurrentSourceFactory,
    // OciRegistryLatestSourceFactory, SshOsReleaseCurrentSourceFactory) validate a config fragment
    // and, on a VALUE-level defect, build and RETURN a FailedCurrentSource/FailedLatestSource
    // themselves instead of throwing. The resolver must detect that returned Failed*Source and route
    // it through the same degrade path as a thrown exception: record one ConfigError of the matching
    // scope carrying its message, and return the SAME instance rather than wrapping it in a fresh
    // Failed*Source. This is an operator config error, not our defect, so it must not be logged at
    // ERROR either.

    @Test
    void factoryReturningAFailedCurrentSourceItself_recordsOneCurrentScopeError_withoutLoggingAtError() {
        FakeCurrentFactory http = FakeCurrentFactory.returningFailed("http-json",
                "The 'http-json' current source's 'auth' is incoherent.");
        FakeLatestFactory gh = new FakeLatestFactory("github-release");
        List<ApplicationConfigLoader.AppConfig> apps =
                List.of(app("alpha", source("http-json"), source("github-release")));

        try (TestLogHandler logs = new TestLogHandler(VersionSourceResolver.class.getName())) {
            VersionSourceResolver resolver = new VersionSourceResolver(
                    List.of(http), List.of(gh), apps, new VersionParsers(apps));

            ApplicationSources pair = resolver.applicationSources().get(0);
            assertSame(http.lastProduced, pair.current(),
                    "the resolver must hold the SAME Failed*Source the factory returned, not wrap it "
                            + "in a fresh one");

            assertEquals(
                    List.of(new ConfigError("alpha", ConfigErrorScope.CURRENT,
                            "The 'http-json' current source's 'auth' is incoherent.")),
                    resolver.configErrors());

            assertTrue(logs.recordsAtLevel(Level.SEVERE).isEmpty(),
                    "a factory RETURNING a FailedCurrentSource is an operator config error, not our "
                            + "own defect, and must not be logged at ERROR");
        }
    }

    @Test
    void factoryReturningAFailedLatestSourceItself_recordsOneLatestScopeError_withoutLoggingAtError() {
        FakeCurrentFactory http = new FakeCurrentFactory("http-json");
        FakeLatestFactory gh = FakeLatestFactory.returningFailed("github-release",
                "The 'github-release' latest source could not resolve its registry.");
        List<ApplicationConfigLoader.AppConfig> apps =
                List.of(app("alpha", source("http-json"), source("github-release")));

        try (TestLogHandler logs = new TestLogHandler(VersionSourceResolver.class.getName())) {
            VersionSourceResolver resolver = new VersionSourceResolver(
                    List.of(http), List.of(gh), apps, new VersionParsers(apps));

            ApplicationSources pair = resolver.applicationSources().get(0);
            assertSame(gh.lastProduced, pair.latest(),
                    "the resolver must hold the SAME Failed*Source the factory returned, not wrap it "
                            + "in a fresh one");

            assertEquals(
                    List.of(new ConfigError("alpha", ConfigErrorScope.LATEST,
                            "The 'github-release' latest source could not resolve its registry.")),
                    resolver.configErrors());

            assertTrue(logs.recordsAtLevel(Level.SEVERE).isEmpty(),
                    "a factory RETURNING a FailedLatestSource is an operator config error, not our "
                            + "own defect, and must not be logged at ERROR");
        }
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
        private final RuntimeException toThrow;
        private boolean throwOnlyOnFirstCall;
        private FailedCurrentSource failedToReturn;
        int createCount;
        ApplicationConfigLoader.VersionSource lastCfg;
        CurrentVersionSource lastProduced;

        FakeCurrentFactory(String type) {
            this(type, null);
        }

        private FakeCurrentFactory(String type, RuntimeException toThrow) {
            this.type = type;
            this.toThrow = toThrow;
        }

        /**
         * A factory that, instead of throwing, RETURNS a {@link FailedCurrentSource} of its own —
         * mirroring HttpJsonCurrentSourceFactory / HttpHeaderCurrentSourceFactory /
         * SshOsReleaseCurrentSourceFactory's value-level-defect path.
         */
        static FakeCurrentFactory returningFailed(String type, String message) {
            FakeCurrentFactory factory = new FakeCurrentFactory(type, null);
            factory.failedToReturn = new FailedCurrentSource(message);
            return factory;
        }

        /** A factory whose {@code create(...)} always throws {@code toThrow}, for every app. */
        static FakeCurrentFactory throwing(String type, RuntimeException toThrow) {
            return new FakeCurrentFactory(type, toThrow);
        }

        /**
         * A factory whose {@code create(...)} throws {@code toThrow} only on its FIRST call and
         * builds normally on every call after that — models one app's factory-rejected fragment
         * without affecting a sibling app that shares the same source {@code type}.
         */
        static FakeCurrentFactory throwingOnce(String type, RuntimeException toThrow) {
            FakeCurrentFactory factory = new FakeCurrentFactory(type, toThrow);
            factory.throwOnlyOnFirstCall = true;
            return factory;
        }

        @Override
        public String type() {
            return type;
        }

        @Override
        public CurrentVersionSource create(ApplicationConfigLoader.VersionSource cfg, VersionParser parser) {
            createCount++;
            lastCfg = cfg;
            if (toThrow != null && (!throwOnlyOnFirstCall || createCount == 1)) {
                throw toThrow;
            }
            if (failedToReturn != null) {
                lastProduced = failedToReturn;
                return lastProduced;
            }
            lastProduced = () -> new SemverVersion("1.0.0");
            return lastProduced;
        }
    }

    private static final class FakeLatestFactory implements LatestVersionSourceFactory {
        private final String type;
        private final RuntimeException toThrow;
        private FailedLatestSource failedToReturn;
        int createCount;
        ApplicationConfigLoader.VersionSource lastCfg;
        LatestVersionSource lastProduced;

        FakeLatestFactory(String type) {
            this(type, null);
        }

        private FakeLatestFactory(String type, RuntimeException toThrow) {
            this.type = type;
            this.toThrow = toThrow;
        }

        /** A factory whose {@code create(...)} always throws {@code toThrow}, for every app. */
        static FakeLatestFactory throwing(String type, RuntimeException toThrow) {
            return new FakeLatestFactory(type, toThrow);
        }

        /**
         * A factory that, instead of throwing, RETURNS a {@link FailedLatestSource} of its own —
         * mirroring OciRegistryLatestSourceFactory's value-level-defect path.
         */
        static FakeLatestFactory returningFailed(String type, String message) {
            FakeLatestFactory factory = new FakeLatestFactory(type, null);
            factory.failedToReturn = new FailedLatestSource(message);
            return factory;
        }

        @Override
        public String type() {
            return type;
        }

        @Override
        public LatestVersionSource create(ApplicationConfigLoader.VersionSource cfg, VersionParser parser) {
            createCount++;
            lastCfg = cfg;
            if (toThrow != null) {
                throw toThrow;
            }
            if (failedToReturn != null) {
                lastProduced = failedToReturn;
                return lastProduced;
            }
            lastProduced = () -> new SemverVersion("2.0.0");
            return lastProduced;
        }
    }
}
