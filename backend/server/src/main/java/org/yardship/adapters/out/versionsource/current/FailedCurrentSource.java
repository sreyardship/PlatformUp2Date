package org.yardship.adapters.out.versionsource.current;

import org.yardship.core.domain.primitives.VersionValue;
import org.yardship.core.ports.out.CurrentVersionSource;

import java.io.Closeable;

/**
 * A per-app {@link CurrentVersionSource} representing a config error that should not abort startup
 * (ADR-0032): a VALUE-level misconfiguration a factory's {@code create(cfg)} detected (e.g. an
 * unsupported {@code auth.type}, or {@code basic} auth missing/blank credentials), a factory
 * {@code create(cfg)} throwing, or an unknown/retired config {@code type} with no factory at all.
 * Whichever path produces it, this class is the resolver's own representation of
 * the config error (ADR-0032): every path is recorded as exactly one
 * {@link org.yardship.adapters.out.versionsource.configerror.ConfigError} there, so a factory
 * building this instance directly is an implementation detail, not a second source of truth.
 *
 * <p>Carries a clear message and re-throws it on every {@link #version()} call, so the offending app
 * surfaces as FAILED on every scrape via the existing per-app isolation in
 * {@code ApplicationVersionService.scrape()} — while every other app keeps scraping normally.
 */
public class FailedCurrentSource implements CurrentVersionSource, Closeable {

    private final String message;

    public FailedCurrentSource(String message) {
        this.message = message;
    }

    /** The config-error message this source was constructed with; also thrown by {@link #version()}. */
    public String message() {
        return message;
    }

    @Override
    public VersionValue version() {
        throw new IllegalStateException(message);
    }

    @Override
    public void close() {
        // No-op: there is no underlying resource to release.
    }
}
