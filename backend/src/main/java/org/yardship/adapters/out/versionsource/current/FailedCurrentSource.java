package org.yardship.adapters.out.versionsource.current;

import org.yardship.core.domain.primitives.Version;
import org.yardship.core.ports.out.CurrentVersionSource;

import java.io.Closeable;

/**
 * A per-app {@link CurrentVersionSource} returned by a factory's {@code create(cfg)} when the app's
 * config fragment has a VALUE-level misconfiguration (e.g. an unsupported {@code auth.type}, or
 * {@code basic} auth missing/blank credentials) that should not abort startup.
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

    @Override
    public Version version() {
        throw new IllegalStateException(message);
    }

    @Override
    public void close() {
        // No-op: there is no underlying resource to release.
    }
}
