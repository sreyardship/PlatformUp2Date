package org.yardship.core.ports.out;

/**
 * Outbound port for a cluster-wide mutual-exclusion lock that guards the scrape path.
 *
 * <p>Backed by Valkey {@code SET key value NX EX ttl}: at most one replica holds the lock at a
 * time, so a scrape happens at most once cluster-wide. The TTL bounds a crashed holder — if the
 * winner dies before releasing, the lock auto-expires and a later attempt can win, so there is
 * no permanent deadlock.
 *
 * <p>The contract is a plain {@code tryAcquire()}/{@code release()} pair (rather than a
 * {@code runExclusively(Supplier)} shape) so callers can branch on the boolean: the lock winner
 * scrapes, the losers serve the shared snapshot. Slice 03's manual trigger reuses that lost-lock
 * branch to surface an IN_PROGRESS result, which a {@code Supplier}-based API could not express
 * cleanly.
 *
 * <p>The core never imports Valkey/Redis types — only this port.
 */
public interface ScrapeLock {

    /**
     * Attempt to acquire the cluster-wide scrape lock.
     *
     * @return {@code true} if this caller now holds the lock (and must later {@link #release()} it);
     *         {@code false} if another replica already holds it.
     * @throws RuntimeException if the backing store is unreachable (fail closed).
     */
    boolean tryAcquire();

    /**
     * Release a lock previously acquired by this caller. Releasing is scoped to the holder so a
     * caller never frees a lock it does not own. A release with no lock held is a no-op.
     *
     * @throws RuntimeException if the backing store is unreachable (fail closed).
     */
    void release();
}
