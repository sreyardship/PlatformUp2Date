package org.yardship.integration.adapters.out.scrapestate.valkey;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.yardship.core.domain.primitives.ScrapeSnapshot;
import org.yardship.core.domain.primitives.Version;
import org.yardship.core.domain.primitives.VersionApplication;
import org.yardship.core.ports.out.ScrapeStateStore;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the real {@link org.yardship.adapters.out.scrapestate.valkey.ValkeyScrapeStateStore}
 * adapter against a Valkey container started by Quarkus Dev Services (the {@code quarkus-redis-client}
 * extension auto-starts one for tests — no manual {@code quarkus.redis.hosts} needed).
 *
 * Verifies a write→read round-trip preserves the snapshot shape (apps + lastAttemptAt) and
 * that a safety TTL is set on the backing key so a stuck snapshot eventually expires.
 */
@QuarkusTest
class ValkeyScrapeStateStoreIT {

    @Inject
    ScrapeStateStore sut;

    @Inject
    RedisDataSource redisDataSource;

    @Test
    void writeThenRead_roundTripsSnapshotShape() {
        VersionApplication app = new VersionApplication(
                "argo-cd", new Version("1.0.0"), new Version("2.0.0"));
        Instant attemptAt = Instant.parse("2026-06-15T12:00:00Z");

        sut.write(List.of(app), attemptAt);

        Optional<ScrapeSnapshot> read = sut.read();

        assertTrue(read.isPresent(), "a written snapshot must be readable back");
        ScrapeSnapshot snapshot = read.get();
        assertEquals(attemptAt, snapshot.lastAttemptAt());
        assertEquals(1, snapshot.applications().size());
        VersionApplication roundTripped = snapshot.applications().getFirst();
        assertEquals("argo-cd", roundTripped.name());
        assertEquals("1.0.0", roundTripped.current().value());
        assertEquals("2.0.0", roundTripped.latest().value());
    }

    @Test
    void write_setsSafetyTtlOnTheKey() {
        sut.write(
                List.of(new VersionApplication("argo-cd", new Version("1.0.0"), new Version("2.0.0"))),
                Instant.parse("2026-06-15T12:00:00Z"));

        // A positive TTL proves the safety expiry is applied (no never-expiring snapshot).
        long ttlSeconds = redisDataSource.key().ttl("scrape:snapshot");
        assertTrue(ttlSeconds > 0, "expected a positive safety TTL on the snapshot key, got: " + ttlSeconds);
    }
}
