package org.yardship.unit.core.services;

import org.junit.jupiter.api.Test;
import org.yardship.core.domain.primitives.ConfigError;
import org.yardship.core.domain.primitives.ConfigErrorScope;
import org.yardship.core.ports.out.ConfigErrors;
import org.yardship.core.services.ConfigErrorService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the configuration-health use case. A Config error is recorded once, when the
 * fleet's Version sources are assembled, and never self-heals — so the service has nothing to
 * recompute, and these tests pin exactly that: each of the three reads forwards to the out-port and
 * returns what came back, unreinterpreted.
 *
 * <p>Driven by a hand-written fake out-port rather than Mockito: the assertion is about pass-through
 * fidelity, so the fake also records which question was asked.
 */
class ConfigErrorServiceTests {

    private static final ConfigError HALF_BROKEN =
            new ConfigError("half-broken-app", ConfigErrorScope.CURRENT, "blank url");
    private static final ConfigError CALVER_BROKEN =
            new ConfigError("calver-broken-app", ConfigErrorScope.APP, "invalid calver-format");

    @Test
    void configErrorsForAnApp_forwardsTheAppNameAndReturnsWhatTheOutPortGave() {
        FakeConfigErrors errors = new FakeConfigErrors(List.of(HALF_BROKEN, CALVER_BROKEN));
        ConfigErrorService sut = new ConfigErrorService(errors);

        List<ConfigError> result = sut.configErrorsFor("half-broken-app");

        assertEquals("half-broken-app", errors.lastAppAsked, "the app name must reach the out-port");
        assertEquals(List.of(HALF_BROKEN), result);
    }

    @Test
    void configErrorsForAnApp_returnsEmpty_whenThatAppIsUnaffected() {
        ConfigErrorService sut = new ConfigErrorService(new FakeConfigErrors(List.of(HALF_BROKEN)));

        assertTrue(sut.configErrorsFor("clean-app").isEmpty());
    }

    @Test
    void allConfigErrors_returnsTheRecordedListUntouched_inRecordedOrder() {
        List<ConfigError> recorded = List.of(HALF_BROKEN, CALVER_BROKEN);
        ConfigErrorService sut = new ConfigErrorService(new FakeConfigErrors(recorded));

        List<ConfigError> result = sut.allConfigErrors();

        assertSame(recorded, result, "the service must not copy, filter or reorder");
        assertEquals(List.of(HALF_BROKEN, CALVER_BROKEN), result);
    }

    @Test
    void allConfigErrors_isEmpty_whenTheConfigIsClean() {
        ConfigErrorService sut = new ConfigErrorService(new FakeConfigErrors(List.of()));

        assertTrue(sut.allConfigErrors().isEmpty());
    }

    @Test
    void unnamedAppCount_forwardsTheFleetWideCount() {
        FakeConfigErrors errors = new FakeConfigErrors(List.of());
        errors.unnamedAppCount = 4;
        ConfigErrorService sut = new ConfigErrorService(errors);

        assertEquals(4, sut.unnamedAppCount());
    }

    /** A fake out-port: answers from a fixed list and records the app name it was asked about. */
    private static final class FakeConfigErrors implements ConfigErrors {

        private final List<ConfigError> recorded;
        private int unnamedAppCount;
        private String lastAppAsked;

        private FakeConfigErrors(List<ConfigError> recorded) {
            this.recorded = recorded;
        }

        @Override
        public List<ConfigError> all() {
            return recorded;
        }

        @Override
        public List<ConfigError> forApp(String applicationName) {
            lastAppAsked = applicationName;
            return recorded.stream()
                    .filter(error -> error.application().equals(applicationName))
                    .toList();
        }

        @Override
        public List<ConfigError> forScope(ConfigErrorScope scope) {
            return recorded.stream()
                    .filter(error -> error.scope() == scope)
                    .toList();
        }

        @Override
        public int unnamedAppCount() {
            return unnamedAppCount;
        }
    }
}
