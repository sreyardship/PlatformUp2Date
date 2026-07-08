package org.yardship.core.ports.out;

import org.yardship.core.domain.primitives.ScrapeSnapshot;
import org.yardship.core.domain.primitives.VersionApplication;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Outbound port for the shared scrape snapshot held outside the JVM (in Valkey).
 *
 * <p>Implementations MUST fail closed: when the backing store is unreachable, both
 * {@link #read()} and {@link #write} throw rather than returning a per-instance fallback.
 * The entry carries a safety TTL so a stuck snapshot eventually expires.
 *
 * <p>The core never imports Valkey/Redis types — only this port.
 */
public interface ScrapeStateStore {

    /**
     * @return the current snapshot, or empty if none has been written yet.
     * @throws RuntimeException if the backing store is unreachable (fail closed).
     */
    Optional<ScrapeSnapshot> read();

    /**
     * Persist a new snapshot. {@code attemptAt} is the instant the producing scrape was attempted.
     *
     * @throws RuntimeException if the backing store is unreachable (fail closed).
     */
    void write(List<VersionApplication> applications, Instant attemptAt);
}
