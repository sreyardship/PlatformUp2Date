package org.yardship.unit.adapters.out.versionsource.latest;

import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.latest.FailedLatestSource;
import org.yardship.core.ports.out.LatestVersionSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link FailedLatestSource} — the no-op {@link LatestVersionSource} returned by
 * {@code OciRegistryLatestSourceFactory} (issue 02) when an app's {@code auth} config is malformed
 * at the VALUE level (unknown {@code type}, or {@code basic} missing/blank credentials). It carries
 * a clear message and throws it on every {@code version()} call, so the app surfaces as FAILED every
 * scrape via the existing per-app isolation in {@code ApplicationVersionService.scrape()} — instead
 * of a misleading error a missing credential would otherwise produce.
 *
 * <p>Mirrors {@link org.yardship.unit.adapters.out.versionsource.current.FailedCurrentSourceTests}.
 */
class FailedLatestSourceTests {

    @Test
    void implementsLatestVersionSource() {
        LatestVersionSource source = new FailedLatestSource("boom");

        assertTrue(source instanceof LatestVersionSource);
    }

    @Test
    void version_throwsWithTheCarriedMessage_everyCall() {
        FailedLatestSource source = new FailedLatestSource(
                "The 'oci-registry' latest source's auth.type 'bearer' is not supported.");

        IllegalStateException first = assertThrows(IllegalStateException.class, source::version);
        assertEquals("The 'oci-registry' latest source's auth.type 'bearer' is not supported.",
                first.getMessage());

        // Every scrape re-throws — not just the first call.
        IllegalStateException second = assertThrows(IllegalStateException.class, source::version);
        assertEquals(first.getMessage(), second.getMessage());
    }

    @Test
    void close_isNoOp_doesNotThrow() {
        FailedLatestSource source = new FailedLatestSource("any message");

        // close() must never throw — there are no resources to release
        source.close();
    }
}
