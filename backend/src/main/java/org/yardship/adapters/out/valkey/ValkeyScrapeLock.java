package org.yardship.adapters.out.valkey;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.value.SetArgs;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.yardship.core.ports.out.ScrapeLock;

import java.util.UUID;

/**
 * Valkey-backed {@link ScrapeLock}. Acquires with {@code SET key token NX EX ttl} and releases by
 * deleting the key only if it still carries this holder's token (scoped, compare-and-delete).
 *
 * <p>{@link #tryAcquire()} mints a fresh per-acquire token (a UUID), remembers it, and lets Valkey
 * arbitrate via {@code NX}: {@code setAndChanged} returns {@code true} only for the single caller
 * that set the key, so at most one replica wins cluster-wide. The {@code EX} TTL bounds a crashed
 * holder — the lock auto-expires rather than deadlocking the cluster forever.
 *
 * <p>{@link #release()} reads the key and deletes it only when it still holds this holder's token,
 * so a caller never frees a successor's lock (e.g. after its own TTL expired and another replica
 * re-acquired). There is a small read-then-delete race; the EVAL-based atomic form would close it,
 * but the get-then-conditional-del form is sufficient here and keeps the adapter on the typed
 * {@link RedisDataSource} API. A release with no token held is a no-op.
 *
 * <p>Both methods fail closed: a Valkey error surfaces as a {@link RuntimeException} rather than a
 * silent {@code false}, matching {@link ValkeyScrapeStateStore}'s fail-closed style.
 */
@ApplicationScoped
public class ValkeyScrapeLock implements ScrapeLock {

    static final String KEY = "scrape:lock";

    // Bounds a crashed holder while comfortably exceeding a normal scrape, so the lock never
    // deadlocks the cluster but also is not released out from under an in-flight scrape.
    private static final long TTL_SECONDS = 30;

    private final ValueCommands<String, String> values;
    private final KeyCommands<String> keys;

    private volatile String heldToken;

    @Inject
    public ValkeyScrapeLock(RedisDataSource redisDataSource) {
        this.values = redisDataSource.value(String.class, String.class);
        this.keys = redisDataSource.key();
    }

    @Override
    public boolean tryAcquire() {
        String token = UUID.randomUUID().toString();
        boolean acquired = values.setAndChanged(KEY, token, new SetArgs().nx().ex(TTL_SECONDS));
        if (acquired) {
            heldToken = token;
        }
        return acquired;
    }

    @Override
    public void release() {
        String token = heldToken;
        if (token == null) {
            return;
        }
        if (token.equals(values.get(KEY))) {
            keys.del(KEY);
        }
        heldToken = null;
    }
}
