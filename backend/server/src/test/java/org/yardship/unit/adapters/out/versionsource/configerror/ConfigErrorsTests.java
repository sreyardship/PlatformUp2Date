package org.yardship.unit.adapters.out.versionsource.configerror;

import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.configerror.ConfigError;
import org.yardship.adapters.out.versionsource.configerror.ConfigErrorScope;
import org.yardship.adapters.out.versionsource.configerror.ConfigErrorSource;
import org.yardship.adapters.out.versionsource.configerror.ConfigErrors;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ConfigErrors} — the single read model every Surface projects, built by
 * aggregating every discovered {@link ConfigErrorSource} (plan.md's CDI-discovery-by-mere-existence
 * idiom, mirroring how {@code VersionSourceResolver} discovers factories).
 *
 * <p><b>Test seam:</b> the production constructor injects {@code Instance<ConfigErrorSource>}; to
 * unit-test without a CDI container, {@link ConfigErrors} exposes a test-visible constructor that
 * accepts a plain {@code Collection<ConfigErrorSource>} — driven entirely by fake sources here, no
 * Quarkus context.
 */
class ConfigErrorsTests {

    @Test
    void aggregatesErrorsAcrossMultipleSources() {
        ConfigErrorSource resolverLike = fixed(
                new ConfigError("alpha", ConfigErrorScope.CURRENT, "blank url"),
                new ConfigError("beta", ConfigErrorScope.LATEST, "unknown type 'mystery'"));
        ConfigErrorSource parsersLike = fixed(
                new ConfigError("gamma", ConfigErrorScope.APP, "invalid calver-format"));

        ConfigErrors configErrors = new ConfigErrors(List.of(resolverLike, parsersLike));

        assertEquals(3, configErrors.all().size());
        assertTrue(configErrors.all().contains(new ConfigError("alpha", ConfigErrorScope.CURRENT, "blank url")));
        assertTrue(configErrors.all().contains(
                new ConfigError("beta", ConfigErrorScope.LATEST, "unknown type 'mystery'")));
        assertTrue(configErrors.all().contains(
                new ConfigError("gamma", ConfigErrorScope.APP, "invalid calver-format")));
    }

    @Test
    void lookupByApp_returnsOnlyThatApplicationsErrors() {
        ConfigErrorSource source = fixed(
                new ConfigError("alpha", ConfigErrorScope.CURRENT, "blank url"),
                new ConfigError("alpha", ConfigErrorScope.LATEST, "unreachable host"),
                new ConfigError("beta", ConfigErrorScope.CURRENT, "unknown type 'mystery'"));

        ConfigErrors configErrors = new ConfigErrors(List.of(source));

        List<ConfigError> alphaErrors = configErrors.forApp("alpha");
        assertEquals(2, alphaErrors.size());
        assertTrue(alphaErrors.stream().allMatch(e -> e.application().equals("alpha")));
    }

    @Test
    void lookupByApp_isEmpty_forAnUnaffectedApp() {
        ConfigErrorSource source = fixed(new ConfigError("alpha", ConfigErrorScope.CURRENT, "blank url"));

        ConfigErrors configErrors = new ConfigErrors(List.of(source));

        assertTrue(configErrors.forApp("clean-app").isEmpty());
    }

    @Test
    void lookupByScope_returnsOnlyErrorsOfThatScope() {
        ConfigErrorSource source = fixed(
                new ConfigError("alpha", ConfigErrorScope.CURRENT, "blank url"),
                new ConfigError("beta", ConfigErrorScope.LATEST, "unreachable host"),
                new ConfigError("gamma", ConfigErrorScope.CURRENT, "unknown type 'mystery'"));

        ConfigErrors configErrors = new ConfigErrors(List.of(source));

        List<ConfigError> currentErrors = configErrors.forScope(ConfigErrorScope.CURRENT);
        assertEquals(2, currentErrors.size());
        assertTrue(currentErrors.stream().allMatch(e -> e.scope() == ConfigErrorScope.CURRENT));
    }

    @Test
    void lookupByScope_isEmpty_whenNoErrorOfThatScopeExists() {
        ConfigErrorSource source = fixed(new ConfigError("alpha", ConfigErrorScope.CURRENT, "blank url"));

        ConfigErrors configErrors = new ConfigErrors(List.of(source));

        assertTrue(configErrors.forScope(ConfigErrorScope.CHANGELOG).isEmpty());
    }

    @Test
    void isEmpty_forACleanConfig_withNoErrorsFromAnySource() {
        ConfigErrorSource cleanResolver = fixed();
        ConfigErrorSource cleanParsers = fixed();

        ConfigErrors configErrors = new ConfigErrors(List.of(cleanResolver, cleanParsers));

        assertTrue(configErrors.all().isEmpty());
        assertTrue(configErrors.forApp("anything").isEmpty());
        assertTrue(configErrors.forScope(ConfigErrorScope.CURRENT).isEmpty());
    }

    @Test
    void isEmpty_whenNoSourcesAreDiscoveredAtAll() {
        ConfigErrors configErrors = new ConfigErrors(List.of());

        assertTrue(configErrors.all().isEmpty());
    }

    private static ConfigErrorSource fixed(ConfigError... errors) {
        List<ConfigError> list = List.of(errors);
        return () -> list;
    }
}
