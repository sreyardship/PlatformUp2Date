package org.yardship.unit.adapters.out.versionsource;

import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.FailedCurrentSource;
import org.yardship.core.ports.out.CurrentVersionSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link FailedCurrentSource} — the no-op {@link CurrentVersionSource} returned by
 * {@code HttpCurrentSourceFactory} (issue 02) when an app's {@code auth} config is malformed at the
 * VALUE level (unknown {@code type}, or {@code basic} missing/blank credentials). It carries a clear
 * message and throws it on every {@code version()} call, so the app surfaces as FAILED every scrape
 * via the existing per-app isolation in {@code ApplicationVersionService.scrape()} — instead of the
 * misleading "version-key did not resolve" error a missing credential would otherwise produce.
 */
class FailedCurrentSourceTests {

    @Test
    void implementsCurrentVersionSource() {
        CurrentVersionSource source = new FailedCurrentSource("boom");

        assertTrue(source instanceof CurrentVersionSource);
    }

    @Test
    void version_throwsWithTheCarriedMessage_everyCall() {
        FailedCurrentSource source = new FailedCurrentSource(
                "The 'http' current source's auth.type 'oauth2' is not supported.");

        IllegalStateException first = assertThrows(IllegalStateException.class, source::version);
        assertEquals("The 'http' current source's auth.type 'oauth2' is not supported.", first.getMessage());

        // Every scrape re-throws — not just the first call.
        IllegalStateException second = assertThrows(IllegalStateException.class, source::version);
        assertEquals(first.getMessage(), second.getMessage());
    }
}
