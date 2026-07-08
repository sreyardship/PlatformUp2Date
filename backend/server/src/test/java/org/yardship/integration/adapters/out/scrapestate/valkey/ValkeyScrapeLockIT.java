package org.yardship.integration.adapters.out.scrapestate.valkey;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yardship.core.ports.out.ScrapeLock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the real {@link org.yardship.adapters.out.scrapestate.valkey.ValkeyScrapeLock} adapter
 * against a Valkey container started by Quarkus Dev Services (the {@code quarkus-redis-client}
 * extension auto-starts one for tests — no manual {@code quarkus.redis.hosts} needed).
 *
 * <p>Verifies the cluster-wide mutual exclusion contract: while the lock is held a second acquire
 * fails, and after release a subsequent acquire succeeds. Backed by {@code SET key value NX EX ttl}.
 */
@QuarkusTest
class ValkeyScrapeLockIT {

    @Inject
    ScrapeLock sut;

    @Inject
    RedisDataSource redisDataSource;

    @BeforeEach
    void clearLockKey() {
        // Ensure a clean slate: a leftover key from another test would mask the contract.
        redisDataSource.key().del("scrape:lock");
    }

    @Test
    void secondAcquireFails_whileLockIsHeld() {
        assertTrue(sut.tryAcquire(), "the first acquire on a free lock must win");
        assertFalse(sut.tryAcquire(), "a second acquire while the lock is held must lose (SET NX)");
    }

    @Test
    void releaseFreesTheLock_soNextAcquireWins() {
        assertTrue(sut.tryAcquire(), "the first acquire must win");

        sut.release();

        assertTrue(sut.tryAcquire(), "after release the lock must be free to acquire again");
    }

    @Test
    void acquiredLockHasAPositiveTtl_soACrashedHolderCannotDeadlock() {
        assertTrue(sut.tryAcquire());

        // A positive TTL proves the NX EX expiry is applied: if the holder dies before releasing,
        // the lock auto-expires rather than deadlocking the cluster forever.
        long ttlSeconds = redisDataSource.key().ttl("scrape:lock");
        assertTrue(ttlSeconds > 0, "expected a positive TTL on the held lock key, got: " + ttlSeconds);
    }
}
