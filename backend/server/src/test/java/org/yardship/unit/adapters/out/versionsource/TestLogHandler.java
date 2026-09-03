package org.yardship.unit.adapters.out.versionsource;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Captures {@code java.util.logging} records emitted by a named logger, so a plain (no-Quarkus-
 * context) unit test can assert on log LEVEL without depending on a particular SLF4J/JBoss-Logging
 * backend class.
 *
 * <p>{@code backend/server/build.gradle}'s test task sets
 * {@code java.util.logging.manager=org.jboss.logmanager.LogManager}, which installs JBoss
 * LogManager as the JVM-wide {@code java.util.logging.LogManager} for the whole test run. Both
 * SLF4J ({@code org.slf4j.Logger}, used by e.g. {@code VersionSourceResolver}) and JBoss Logging
 * route through it, so attaching a plain {@link Handler} to
 * {@code java.util.logging.Logger.getLogger(loggerName)} observes everything logged under that
 * name regardless of which facade the production code calls. SLF4J levels map onto
 * {@code java.util.logging.Level} the standard JBoss LogManager way: {@code WARN -> WARNING},
 * {@code ERROR -> SEVERE}.
 *
 * <p>Usage: {@code try (var logs = new TestLogHandler(VersionSourceResolver.class.getName())) { ...
 * assert on logs.recordsAtLevel(Level.SEVERE) ... }} — always used in try-with-resources so the
 * handler is detached again and does not leak into other tests sharing the same JVM-wide
 * LogManager.
 */
public final class TestLogHandler implements AutoCloseable {

    private final Logger julLogger;
    private final Handler handler;
    private final List<LogRecord> records = new ArrayList<>();

    public TestLogHandler(String loggerName) {
        this.julLogger = Logger.getLogger(loggerName);
        this.julLogger.setLevel(Level.ALL);
        this.handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
                // No buffering to flush.
            }

            @Override
            public void close() {
                // Nothing to release; detaching happens via TestLogHandler#close.
            }
        };
        this.handler.setLevel(Level.ALL);
        this.julLogger.addHandler(handler);
    }

    /** Every record captured so far, oldest first. */
    public List<LogRecord> records() {
        return List.copyOf(records);
    }

    /** Every record captured so far at exactly {@code level}. */
    public List<LogRecord> recordsAtLevel(Level level) {
        return records.stream().filter(record -> record.getLevel().equals(level)).toList();
    }

    @Override
    public void close() {
        julLogger.removeHandler(handler);
    }
}
