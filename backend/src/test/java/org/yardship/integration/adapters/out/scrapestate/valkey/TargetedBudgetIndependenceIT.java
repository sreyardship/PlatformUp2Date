package org.yardship.integration.adapters.out.scrapestate.valkey;

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
import org.yardship.core.ports.out.TargetedScrapeBudget;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test proving the full-scrape and targeted-scrape rolling-window budgets (issue 03) are
 * INDEPENDENT: each is a distinct {@link ScrapeRateLimiter} bean over its own Valkey ZSET key
 * ({@code scrape:budget} vs. {@code scrape:targeted:budget}), so draining one must never affect the
 * other's admission decisions.
 *
 * <p>Mirrors {@link ValkeyScrapeRateLimiterIT}'s profile/config-override style, but shrinks BOTH
 * budgets (to different small caps, so a test bug swapping the two keys would also be caught) and
 * exercises both qualified beans side by side.
 */
@QuarkusTest
@TestProfile(TargetedBudgetIndependenceIT.SmallBudgetsProfile.class)
class TargetedBudgetIndependenceIT {

    private static final int FULL_MAX = 2;
    private static final int TARGETED_MAX = 4;
    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");

    @Inject
    @FullScrapeBudget
    ScrapeRateLimiter fullBudget;

    @Inject
    @TargetedScrapeBudget
    ScrapeRateLimiter targetedBudget;

    @Inject
    RedisDataSource redisDataSource;

    @BeforeEach
    void clearBudgetKeys() {
        redisDataSource.key().del("scrape:budget");
        redisDataSource.key().del("scrape:targeted:budget");
    }

    @Test
    void drainingTheFullBudget_doesNotAffectTheTargetedBudget() {
        for (int i = 0; i < FULL_MAX; i++) {
            assertTrue(fullBudget.tryAcquire(NOW).allowed(), "priming admission #" + i + " on the full budget");
        }
        Decision fullExhausted = fullBudget.tryAcquire(NOW);
        assertFalse(fullExhausted.allowed(), "the full budget must now be exhausted");

        // The targeted budget must still admit up to ITS OWN cap, unaffected by the full budget.
        for (int i = 0; i < TARGETED_MAX; i++) {
            assertTrue(targetedBudget.tryAcquire(NOW).allowed(),
                    "targeted admission #" + i + " must succeed even though the full budget is drained");
        }
        assertFalse(targetedBudget.tryAcquire(NOW).allowed(), "the targeted budget has its own, separate cap");
    }

    @Test
    void drainingTheTargetedBudget_doesNotAffectTheFullBudget() {
        for (int i = 0; i < TARGETED_MAX; i++) {
            assertTrue(targetedBudget.tryAcquire(NOW).allowed(), "priming admission #" + i + " on the targeted budget");
        }
        Decision targetedExhausted = targetedBudget.tryAcquire(NOW);
        assertFalse(targetedExhausted.allowed(), "the targeted budget must now be exhausted");

        // The full budget must still admit up to ITS OWN cap, unaffected by the targeted budget.
        for (int i = 0; i < FULL_MAX; i++) {
            assertTrue(fullBudget.tryAcquire(NOW).allowed(),
                    "full-budget admission #" + i + " must succeed even though the targeted budget is drained");
        }
        assertFalse(fullBudget.tryAcquire(NOW).allowed(), "the full budget has its own, separate cap");
    }

    @Test
    void theTwoBudgetsAreBackedByDistinctValkeyKeys() {
        fullBudget.tryAcquire(NOW);

        long fullKeyCard = redisDataSource.execute("ZCARD", "scrape:budget").toLong();
        long targetedKeyCard = redisDataSource.execute("ZCARD", "scrape:targeted:budget").toLong();

        assertTrue(fullKeyCard >= 1, "spending from the full budget must record an entry under scrape:budget");
        assertTrue(targetedKeyCard == 0,
                "spending from the full budget must NOT record anything under scrape:targeted:budget");
    }

    /**
     * Shrinks BOTH budgets to small, DIFFERENT caps so the rolling-window contract is observable for
     * each independently, and a test bug that swapped the two keys/configs would surface as a wrong
     * cap rather than a coincidentally-passing test.
     */
    public static class SmallBudgetsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.ofEntries(
                    Map.entry("platform-config.scrape-trigger.max-per-window", String.valueOf(FULL_MAX)),
                    Map.entry("platform-config.scrape-trigger.window", "30s"),
                    Map.entry("platform-config.targeted-scrape-trigger.max-per-window", String.valueOf(TARGETED_MAX)),
                    Map.entry("platform-config.targeted-scrape-trigger.window", "30s"),
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
