package org.yardship.adapters.out.valkey;

import io.quarkus.redis.datasource.RedisDataSource;
import io.vertx.mutiny.redis.client.Response;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.yardship.adapters.out.versionclient.ApplicationConfigLoader;
import org.yardship.core.ports.out.ScrapeRateLimiter;

import java.time.Instant;
import java.util.UUID;

/**
 * Valkey-backed {@link ScrapeRateLimiter}. Keeps a ZSET of manual-trigger timestamps at
 * {@code scrape:budget} (score = epoch millis of {@code now}, member = {@code now:<random>} so two
 * triggers at the same millisecond do not collapse to one ZSET member) and evaluates the rolling
 * window with ONE atomic Lua script: evict entries older than {@code now - window}, count the rest,
 * and if under {@code max-per-window} add {@code now} and return the remaining budget; otherwise
 * return the {@code retryAfter} derived from the oldest in-window entry.
 *
 * <p>{@code now} is supplied by the caller (the service passes {@code clock.instant()}) and handed to
 * the script via {@code ARGV} — the window is deterministic and never depends on Redis {@code TIME}.
 * A safety TTL ({@code window * 2}) is set on the key so an idle budget never lingers forever.
 *
 * <p>Fail-closed: a Valkey error surfaces as a {@link RuntimeException} rather than a silent allow.
 */
@ApplicationScoped
public class ValkeyScrapeRateLimiter implements ScrapeRateLimiter {

    static final String KEY = "scrape:budget";

    /**
     * Single atomic rolling-window decision.
     *
     * <p>KEYS[1] = the budget ZSET. ARGV[1] = now (epoch millis), ARGV[2] = window (millis),
     * ARGV[3] = max-per-window, ARGV[4] = a unique member for this trigger.
     *
     * <p>Returns {@code {allowed, remaining, windowResetsInSeconds, retryAfterSeconds}} — all
     * integers; the two "seconds" values are ceil'd and clamped to a minimum of 1 when positive.
     */
    private static final String TRY_ACQUIRE_LUA = """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local max = tonumber(ARGV[3])
            local member = ARGV[4]

            redis.call('ZREMRANGEBYSCORE', key, 0, now - window)
            local count = redis.call('ZCARD', key)

            if count < max then
                redis.call('ZADD', key, now, member)
                redis.call('PEXPIRE', key, window * 2)
                local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
                local oldestScore = tonumber(oldest[2])
                local resetsMs = (oldestScore + window) - now
                local resetsSec = math.max(1, math.ceil(resetsMs / 1000))
                return {1, max - (count + 1), resetsSec, 0}
            else
                local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
                local oldestScore = tonumber(oldest[2])
                local retryMs = (oldestScore + window) - now
                local retrySec = math.max(1, math.ceil(retryMs / 1000))
                return {0, 0, 0, retrySec}
            end
            """;

    private final RedisDataSource redisDataSource;
    private final ApplicationConfigLoader config;

    @Inject
    public ValkeyScrapeRateLimiter(RedisDataSource redisDataSource, ApplicationConfigLoader config) {
        this.redisDataSource = redisDataSource;
        this.config = config;
    }

    @Override
    public Decision tryAcquire(Instant now) {
        long nowMillis = now.toEpochMilli();
        long windowMillis = config.scrapeTrigger().window().toMillis();
        int max = config.scrapeTrigger().maxPerWindow();
        String member = nowMillis + ":" + UUID.randomUUID();

        Response response = redisDataSource.execute(
                "EVAL",
                TRY_ACQUIRE_LUA,
                "1",
                KEY,
                Long.toString(nowMillis),
                Long.toString(windowMillis),
                Integer.toString(max),
                member);

        boolean allowed = response.get(0).toInteger() == 1;
        if (allowed) {
            return Decision.allowed(response.get(1).toInteger(), response.get(2).toLong());
        }
        return Decision.rejected(response.get(3).toLong());
    }

    @Override
    public Budget peek(Instant now) {
        long nowMillis = now.toEpochMilli();
        long windowMillis = config.scrapeTrigger().window().toMillis();
        int max = config.scrapeTrigger().maxPerWindow();
        long windowStart = nowMillis - windowMillis;

        Response oldest = redisDataSource.execute(
                "ZRANGEBYSCORE", KEY, Long.toString(windowStart), "+inf", "WITHSCORES", "LIMIT", "0", "1");
        int inWindow = Integer.parseInt(
                redisDataSource.execute("ZCOUNT", KEY, Long.toString(windowStart), "+inf").toString());

        int remaining = Math.max(0, max - inWindow);
        long windowResetsIn = 0;
        if (oldest != null && oldest.size() >= 2) {
            long oldestScore = oldest.get(1).toLong();
            long resetsMs = (oldestScore + windowMillis) - nowMillis;
            windowResetsIn = Math.max(1, (long) Math.ceil(resetsMs / 1000.0));
        }
        return new Budget(remaining, windowResetsIn);
    }
}
