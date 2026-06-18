package org.yardship.integration.adapters.out.valkey;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yardship.core.ports.out.FullScrapeBudget;
import org.yardship.core.ports.out.ScrapeRateLimiter;
import org.yardship.core.ports.out.ScrapeRateLimiter.Decision;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the real {@link org.yardship.adapters.out.valkey.ValkeyScrapeRateLimiter}
 * against a Valkey container started by Quarkus Dev Services. Pins the rolling-window contract end to
 * end, evaluated by the adapter's single atomic Lua script:
 *
 * <ul>
 *   <li>the first N triggers in a window are admitted with a decreasing {@code remaining};</li>
 *   <li>the (N+1)th at the same instant is rejected with a positive {@code retryAfter};</li>
 *   <li>advancing {@code now} past the window evicts the aged entries, freeing slots again.</li>
 * </ul>
 *
 * <p>A {@link SmallBudgetProfile} shrinks the budget to {@code max-per-window=3} over a short window so
 * the behavior is observable without flakiness; {@code now} is supplied explicitly so the window is
 * deterministic (no reliance on wall-clock timing).
 */
@QuarkusTest
@TestProfile(ValkeyScrapeRateLimiterIT.SmallBudgetProfile.class)
class ValkeyScrapeRateLimiterIT {

    private static final int MAX = 3;
    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");

    @Inject
    @FullScrapeBudget
    ScrapeRateLimiter sut;

    @Inject
    RedisDataSource redisDataSource;

    @BeforeEach
    void clearBudgetKey() {
        // A leftover ZSET from another test would mask the rolling-window contract.
        redisDataSource.key().del("scrape:budget");
    }

    @Test
    void firstNTriggersAreAdmittedWithDecreasingRemaining() {
        for (int spent = 1; spent <= MAX; spent++) {
            Decision decision = sut.tryAcquire(NOW);

            assertTrue(decision.allowed(), "trigger #" + spent + " within the budget must be admitted");
            assertEquals(MAX - spent, decision.remaining(),
                    "remaining must decrease by one per admitted trigger");
        }
    }

    @Test
    void theTriggerPastTheBudgetIsRejectedWithAPositiveRetryAfter() {
        for (int i = 0; i < MAX; i++) {
            assertTrue(sut.tryAcquire(NOW).allowed());
        }

        Decision rejected = sut.tryAcquire(NOW);

        assertFalse(rejected.allowed(), "the (N+1)th trigger in the window must be rejected");
        assertTrue(rejected.retryAfter() > 0,
                "a rejected trigger must report a positive retryAfter, got: " + rejected.retryAfter());
    }

    @Test
    void advancingNowPastTheWindowEvictsAgedEntriesAndFreesSlots() {
        for (int i = 0; i < MAX; i++) {
            assertTrue(sut.tryAcquire(NOW).allowed());
        }
        assertFalse(sut.tryAcquire(NOW).allowed(), "budget is exhausted at NOW");

        // Move well past the configured window so every recorded entry has aged out.
        Instant afterWindow = NOW.plusSeconds(3600);

        Decision afterEviction = sut.tryAcquire(afterWindow);

        assertTrue(afterEviction.allowed(),
                "once the window has elapsed the aged entries are evicted and a slot frees");
        assertEquals(MAX - 1, afterEviction.remaining(),
                "after eviction the window holds only this fresh trigger");
    }

    /**
     * Shrinks the manual-scrape budget to {@code max-per-window=3} over a short window so the
     * rolling-window contract is observable. Mirrors {@code WireMockTestProfile}'s override style.
     */
    public static class SmallBudgetProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.ofEntries(
                    Map.entry("platform-config.scrape-trigger.max-per-window", String.valueOf(MAX)),
                    Map.entry("platform-config.scrape-trigger.window", "30s"),
                    // Keep the @ConfigMapping resolvable when the app boots.
                    Map.entry("platform-config.scrape-interval", "1h"),
                    Map.entry("platform-config.apps[0].name", "test-app"),
                    Map.entry("platform-config.apps[0].current.type", "http"),
                    Map.entry("platform-config.apps[0].current.url", "https://example.test/version"),
                    Map.entry("platform-config.apps[0].latest.type", "github-release"),
                    Map.entry("platform-config.apps[0].latest.url", "https://example.test/latest")
            );
        }
    }
}
