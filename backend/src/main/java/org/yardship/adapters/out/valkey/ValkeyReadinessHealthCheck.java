package org.yardship.adapters.out.valkey;

import io.quarkus.redis.datasource.RedisDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * Readiness probe that reports DOWN when Valkey is unreachable. Because the read path
 * fails closed (503) on a Valkey outage, readiness must reflect that the instance cannot
 * serve {@code GET /api/v1/version} so traffic is steered away until Valkey recovers.
 */
@Readiness
@ApplicationScoped
public class ValkeyReadinessHealthCheck implements HealthCheck {

    private static final String NAME = "Valkey scrape-state store";

    private final RedisDataSource redisDataSource;

    @Inject
    public ValkeyReadinessHealthCheck(RedisDataSource redisDataSource) {
        this.redisDataSource = redisDataSource;
    }

    @Override
    public HealthCheckResponse call() {
        try {
            String pong = redisDataSource.execute("PING").toString();
            return HealthCheckResponse.named(NAME)
                    .status("PONG".equalsIgnoreCase(pong))
                    .build();
        } catch (RuntimeException e) {
            return HealthCheckResponse.named(NAME)
                    .down()
                    .withData("error", e.getMessage())
                    .build();
        }
    }
}
