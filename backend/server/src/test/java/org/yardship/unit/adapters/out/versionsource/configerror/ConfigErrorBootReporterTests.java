package org.yardship.unit.adapters.out.versionsource.configerror;

import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.configerror.ConfigError;
import org.yardship.adapters.out.versionsource.configerror.ConfigErrorBootReporter;
import org.yardship.adapters.out.versionsource.configerror.ConfigErrorScope;
import org.yardship.adapters.out.versionsource.configerror.ConfigErrorSource;
import org.yardship.adapters.out.versionsource.configerror.ConfigErrors;
import org.yardship.unit.adapters.out.versionsource.TestLogHandler;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ConfigErrorBootReporter} — the {@code StartupEvent} observer that replaces
 * the per-factory WARNs scattered through Quarkus startup noise with exactly ONE aggregate WARN
 * (plan.md / issue 01's acceptance criteria).
 *
 * <p><b>Test seam:</b> the production constructor observes {@code StartupEvent} via CDI; to
 * unit-test without a CDI container or firing an actual event, {@link ConfigErrorBootReporter}
 * exposes a test-visible constructor taking a plain {@link ConfigErrors} and an explicit total
 * monitored-app count, plus a public {@code report()} method that performs exactly what the
 * {@code @Observes StartupEvent} method would delegate to. Log assertions go through
 * {@link TestLogHandler}, attached to the reporter's own logger name.
 */
class ConfigErrorBootReporterTests {

    @Test
    void emitsExactlyOneAggregateWarn_listingEveryAppScopeAndReason() {
        ConfigErrorSource source = fixed(
                new ConfigError("alpha", ConfigErrorScope.CURRENT, "blank url"),
                new ConfigError("beta", ConfigErrorScope.LATEST, "unknown type 'mystery'"),
                new ConfigError("gamma", ConfigErrorScope.CURRENT, "unreachable host"));
        ConfigErrors configErrors = new ConfigErrors(List.of(source));
        ConfigErrorBootReporter reporter = new ConfigErrorBootReporter(configErrors, 12);

        try (TestLogHandler logs = new TestLogHandler(ConfigErrorBootReporter.class.getName())) {
            reporter.report();

            List<LogRecord> warnings = logs.recordsAtLevel(Level.WARNING);
            assertEquals(1, warnings.size(), "exactly one aggregate WARN must be emitted, not one per error");

            String message = warnings.get(0).getMessage();
            assertTrue(message.contains("3 of 12"),
                    "the header must count DISTINCT affected apps out of the total monitored, "
                            + "not the number of individual errors; was: " + message);
            assertTrue(message.contains("alpha") && message.contains("blank url"));
            assertTrue(message.contains("beta") && message.contains("unknown type 'mystery'"));
            assertTrue(message.contains("gamma") && message.contains("unreachable host"));
        }
    }

    @Test
    void countsEachAffectedApplicationOnce_evenWithTwoErrorsOnTheSameApp() {
        ConfigErrorSource source = fixed(
                new ConfigError("alpha", ConfigErrorScope.CURRENT, "blank url"),
                new ConfigError("alpha", ConfigErrorScope.LATEST, "unreachable host"));
        ConfigErrors configErrors = new ConfigErrors(List.of(source));
        ConfigErrorBootReporter reporter = new ConfigErrorBootReporter(configErrors, 5);

        try (TestLogHandler logs = new TestLogHandler(ConfigErrorBootReporter.class.getName())) {
            reporter.report();

            String message = logs.recordsAtLevel(Level.WARNING).get(0).getMessage();
            assertTrue(message.contains("1 of 5"),
                    "one app with two errors must count as ONE affected application; was: " + message);
        }
    }

    @Test
    void logsNothing_whenTheConfigIsClean() {
        ConfigErrors configErrors = new ConfigErrors(List.of(fixed()));
        ConfigErrorBootReporter reporter = new ConfigErrorBootReporter(configErrors, 12);

        try (TestLogHandler logs = new TestLogHandler(ConfigErrorBootReporter.class.getName())) {
            reporter.report();

            assertTrue(logs.records().isEmpty(), "a clean config must log nothing at all; was: " + logs.records());
        }
    }

    private static ConfigErrorSource fixed(ConfigError... errors) {
        List<ConfigError> list = List.of(errors);
        return () -> list;
    }
}
