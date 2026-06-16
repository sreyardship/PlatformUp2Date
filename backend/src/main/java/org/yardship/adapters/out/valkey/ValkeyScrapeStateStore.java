package org.yardship.adapters.out.valkey;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.SetArgs;
import io.quarkus.redis.datasource.value.ValueCommands;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.yardship.core.domain.primitives.ScrapeSnapshot;
import org.yardship.core.domain.primitives.Version;
import org.yardship.core.domain.primitives.VersionApplication;
import org.yardship.core.ports.out.ScrapeStateStore;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Valkey-backed {@link ScrapeStateStore}. JSON-serialises the snapshot into a single key
 * with a safety TTL, and fails closed (throws {@link ScrapeStateUnavailableException}) when
 * Valkey is unreachable.
 *
 * <p>The snapshot is mapped to a plain-string DTO before serialisation so the domain
 * {@link Version} wrapper round-trips cleanly, and back to the domain on read.
 */
@ApplicationScoped
public class ValkeyScrapeStateStore implements ScrapeStateStore {

    static final String KEY = "scrape:snapshot";

    // Safety expiry well above the scrape interval: a stuck snapshot eventually clears,
    // but a healthy one is always refreshed long before it expires.
    private static final Duration SAFETY_TTL = Duration.ofDays(7);

    private final ValueCommands<String, String> values;
    private final ObjectMapper objectMapper;

    @Inject
    public ValkeyScrapeStateStore(RedisDataSource redisDataSource, ObjectMapper objectMapper) {
        this.values = redisDataSource.value(String.class, String.class);
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<ScrapeSnapshot> read() {
        String json;
        try {
            json = values.get(KEY);
        } catch (RuntimeException e) {
            throw new ScrapeStateUnavailableException("Failed to read scrape snapshot from Valkey", e);
        }

        if (json == null) {
            return Optional.empty();
        }
        return Optional.of(deserialise(json));
    }

    @Override
    public void write(List<VersionApplication> applications, Instant attemptAt) {
        String json = serialise(new SnapshotDTO(toAppDtos(applications), attemptAt.toEpochMilli()));
        try {
            values.set(KEY, json, new SetArgs().ex(SAFETY_TTL));
        } catch (RuntimeException e) {
            throw new ScrapeStateUnavailableException("Failed to write scrape snapshot to Valkey", e);
        }
    }

    private List<AppDTO> toAppDtos(List<VersionApplication> applications) {
        return applications.stream()
                .map(app -> new AppDTO(app.name(), app.current().value(), app.latest().value()))
                .toList();
    }

    private ScrapeSnapshot deserialise(String json) {
        SnapshotDTO dto;
        try {
            dto = objectMapper.readValue(json, SnapshotDTO.class);
        } catch (JsonProcessingException e) {
            throw new ScrapeStateUnavailableException("Failed to deserialise scrape snapshot", e);
        }
        List<VersionApplication> applications = dto.applications().stream()
                .map(app -> new VersionApplication(app.name(), new Version(app.current()), new Version(app.latest())))
                .toList();
        return new ScrapeSnapshot(applications, Instant.ofEpochMilli(dto.lastAttemptAtEpochMillis()));
    }

    private String serialise(SnapshotDTO dto) {
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (JsonProcessingException e) {
            throw new ScrapeStateUnavailableException("Failed to serialise scrape snapshot", e);
        }
    }

    // Jackson (de)serialises these reflectively; in native image they must be registered
    // or the snapshot round-trip throws and the cache never populates.
    @RegisterForReflection
    private record SnapshotDTO(List<AppDTO> applications, long lastAttemptAtEpochMillis) {
    }

    @RegisterForReflection
    private record AppDTO(String name, String current, String latest) {
    }
}
