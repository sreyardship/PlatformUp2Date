package org.yardship.adapters.out.scrapestate.valkey;

import io.quarkus.redis.datasource.RedisDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.core.ports.out.FullScrapeBudget;
import org.yardship.core.ports.out.ScrapeRateLimiter;
import org.yardship.core.ports.out.TargetedScrapeBudget;

/**
 * Composition root for the two rolling-window {@link ScrapeRateLimiter} budgets (issue 03):
 * the full-fleet scrape's budget (Valkey ZSET {@code scrape:budget}, sized from
 * {@code platform-config.scrape-trigger}) and the targeted-scrape budget (Valkey ZSET
 * {@code scrape:targeted:budget}, sized from {@code platform-config.targeted-scrape-trigger}).
 *
 * <p>Each {@link ValkeyScrapeRateLimiter} is handed {@link java.util.function.Supplier}s that read
 * {@link ApplicationConfigLoader} lazily (per call), so config-override test profiles and any future
 * live-reload still apply — the suppliers must NOT capture a value snapshotted at producer-call time.
 *
 * <p>There is deliberately NO unqualified {@code ScrapeRateLimiter} bean: with two genuine budgets an
 * unqualified injection is ambiguous, so every {@code @Inject ScrapeRateLimiter} must pick
 * {@link FullScrapeBudget} or {@link TargetedScrapeBudget} explicitly and an unintended injection fails
 * fast rather than silently binding the full-scrape budget.
 */
public class ScrapeRateLimiterProducer {

    @Inject
    RedisDataSource redisDataSource;

    @Inject
    ApplicationConfigLoader config;

    @Produces
    @ApplicationScoped
    @FullScrapeBudget
    public ScrapeRateLimiter fullScrapeBudget() {
        return new ValkeyScrapeRateLimiter(
                redisDataSource,
                "scrape:budget",
                () -> config.scrapeTrigger().maxPerWindow(),
                () -> config.scrapeTrigger().window());
    }

    @Produces
    @ApplicationScoped
    @TargetedScrapeBudget
    public ScrapeRateLimiter targetedScrapeBudget() {
        return new ValkeyScrapeRateLimiter(
                redisDataSource,
                "scrape:targeted:budget",
                () -> config.targetedScrapeTrigger().maxPerWindow(),
                () -> config.targetedScrapeTrigger().window());
    }
}
