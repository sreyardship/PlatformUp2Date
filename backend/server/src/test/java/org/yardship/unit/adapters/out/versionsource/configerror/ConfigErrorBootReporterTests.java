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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    // --- unnamed-app line in the aggregate report (issue 02 / ADR-0032) -----------------------

    @Test
    void reportsUnnamedAppCount_asItsOwnLine_whenGreaterThanZero() {
        ConfigErrorSource source = withUnnamedApps(3);
        ConfigErrors configErrors = new ConfigErrors(List.of(source));
        ConfigErrorBootReporter reporter = new ConfigErrorBootReporter(configErrors, 12);

        try (TestLogHandler logs = new TestLogHandler(ConfigErrorBootReporter.class.getName())) {
            reporter.report();

            List<LogRecord> warnings = logs.recordsAtLevel(Level.WARNING);
            assertEquals(1, warnings.size(), "still exactly one aggregate WARN");
            String message = warnings.get(0).getMessage();
            assertTrue(message.contains("3 applications have no 'name' configured"),
                    "the unnamed-app line must state the count and that 'name' is missing; was: " + message);
            assertTrue(message.contains("dropped from the fleet entirely"),
                    "the unnamed-app line must state the consequence; was: " + message);
        }
    }

    @Test
    void emitsTheAggregateWarn_whenThereAreOnlyUnnamedApps_andNoOtherConfigErrors() {
        // The report must not stay silent just because ConfigErrors.all() is empty: an unnamed app
        // is real, alertable information even with zero per-app ConfigError entries.
        ConfigErrorSource source = withUnnamedApps(1);
        ConfigErrors configErrors = new ConfigErrors(List.of(source));
        ConfigErrorBootReporter reporter = new ConfigErrorBootReporter(configErrors, 5);

        try (TestLogHandler logs = new TestLogHandler(ConfigErrorBootReporter.class.getName())) {
            reporter.report();

            List<LogRecord> warnings = logs.recordsAtLevel(Level.WARNING);
            assertEquals(1, warnings.size(),
                    "a config with only unnamed apps and no other errors must still emit one WARN");

            String message = warnings.get(0).getMessage();
            assertTrue(message.contains("1 application has no 'name' configured"),
                    "a single unnamed app must read in the singular; was: " + message);
            assertTrue(message.contains("is dropped from the fleet entirely"),
                    "the consequence must agree in number with the count; was: " + message);
            assertFalse(message.contains("of 5 monitored applications"),
                    "with no per-app ConfigError there is no affected-app count to head the report, "
                            + "so it must not claim one; was: " + message);
        }
    }

    @Test
    void reportsBothTheAffectedAppsHeaderAndTheUnnamedLine_whenTheConfigHasEachKind() {
        // The mixed case: a per-app ConfigError AND unnamed apps. The header counts only the apps
        // that HAVE an identity to be counted under; the unnamed line is appended after the
        // per-error lines, because an unnamed app can never be one of them.
        ConfigErrorSource errors = fixed(new ConfigError("alpha", ConfigErrorScope.CURRENT, "blank url"));
        ConfigErrors configErrors = new ConfigErrors(List.of(errors, withUnnamedApps(2)));
        ConfigErrorBootReporter reporter = new ConfigErrorBootReporter(configErrors, 12);

        try (TestLogHandler logs = new TestLogHandler(ConfigErrorBootReporter.class.getName())) {
            reporter.report();

            List<LogRecord> warnings = logs.recordsAtLevel(Level.WARNING);
            assertEquals(1, warnings.size(), "both kinds still share one aggregate WARN");

            String message = warnings.get(0).getMessage();
            assertTrue(message.contains("1 of 12 monitored applications have configuration errors:"),
                    "the header must still count the named affected apps; was: " + message);
            assertTrue(message.contains("alpha") && message.contains("blank url"),
                    "the per-error line must survive alongside the unnamed line; was: " + message);
            assertTrue(message.contains("2 applications have no 'name' configured"),
                    "the unnamed line must be appended, not replaced by the header; was: " + message);
            assertTrue(message.indexOf("alpha") < message.indexOf("no 'name' configured"),
                    "the unnamed line comes after the per-error lines; was: " + message);
        }
    }

    @Test
    void logsNothing_whenTheConfigIsClean_andThereAreNoUnnamedApps() {
        ConfigErrorSource source = withUnnamedApps(0);
        ConfigErrors configErrors = new ConfigErrors(List.of(source));
        ConfigErrorBootReporter reporter = new ConfigErrorBootReporter(configErrors, 12);

        try (TestLogHandler logs = new TestLogHandler(ConfigErrorBootReporter.class.getName())) {
            reporter.report();

            assertTrue(logs.records().isEmpty(),
                    "a clean config with zero unnamed apps must log nothing at all; was: " + logs.records());
        }
    }

    private static ConfigErrorSource fixed(ConfigError... errors) {
        List<ConfigError> list = List.of(errors);
        return () -> list;
    }

    private static ConfigErrorSource withUnnamedApps(int count) {
        return new ConfigErrorSource() {
            @Override
            public List<ConfigError> configErrors() {
                return List.of();
            }

            @Override
            public int unnamedApps() {
                return count;
            }
        };
    }
}
