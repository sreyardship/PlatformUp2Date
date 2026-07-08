package org.yardship.adapters.out.scrapestate.valkey;
import org.yardship.adapters.out.scrapestate.ScrapeStateUnavailableException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.SetArgs;
import io.quarkus.redis.datasource.value.ValueCommands;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yardship.adapters.out.versionsource.VersionParsers;
import org.yardship.core.domain.exceptions.InvalidVersionException;
import org.yardship.core.domain.primitives.ScrapeSnapshot;
import org.yardship.core.domain.primitives.SideObservation;
import org.yardship.core.domain.primitives.VersionApplication;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionValue;
import org.yardship.core.ports.out.ScrapeStateStore;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Valkey-backed {@link ScrapeStateStore}. JSON-serialises the snapshot into a single key
 * with a safety TTL, and fails closed (throws {@link ScrapeStateUnavailableException}) when
 * Valkey is unreachable.
 *
 * <p>The snapshot is mapped to a plain-string DTO before serialisation so the domain
 * {@link org.yardship.core.domain.primitives.VersionValue} wrapper round-trips cleanly. The DTO
 * carries bare strings and epoch-millis timestamps only — no scheme information is persisted.
 * On read, every stored string is retyped via the app's config-derived
 * {@link org.yardship.adapters.out.versionsource.VersionParsers}: scheme is never read from
 * persisted data (ADR-0022).
 */
@ApplicationScoped
public class ValkeyScrapeStateStore implements ScrapeStateStore {

    private final Logger logger = LoggerFactory.getLogger(ValkeyScrapeStateStore.class);

    static final String KEY = "scrape:snapshot";

    // Safety expiry well above the scrape interval: a stuck snapshot eventually clears,
    // but a healthy one is always refreshed long before it expires.
    private static final Duration SAFETY_TTL = Duration.ofDays(7);

    private final ValueCommands<String, String> values;
    private final ObjectMapper objectMapper;
    private final VersionParsers versionParsers;

    @Inject
    public ValkeyScrapeStateStore(RedisDataSource redisDataSource, ObjectMapper objectMapper, VersionParsers versionParsers) {
        this.values = redisDataSource.value(String.class, String.class);
        this.objectMapper = objectMapper;
        this.versionParsers = versionParsers;
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
                .map(app -> new AppDTO(
                        app.name(),
                        app.current().value().map(VersionValue::value).orElse(null),
                        app.current().lastSuccessAt().map(Instant::toEpochMilli).orElse(null),
                        app.current().lastFailureAt().map(Instant::toEpochMilli).orElse(null),
                        app.latest().value().map(VersionValue::value).orElse(null),
                        app.latest().lastSuccessAt().map(Instant::toEpochMilli).orElse(null),
                        app.latest().lastFailureAt().map(Instant::toEpochMilli).orElse(null)))
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
                .flatMap(this::toVersionApplicationOrSkip)
                .toList();
        return new ScrapeSnapshot(applications, Instant.ofEpochMilli(dto.lastAttemptAtEpochMillis()));
    }

    // A config change can remove an app that still has a persisted entry from a prior scrape:
    // that entry is stale, not corrupt, so it is skipped (not thrown) and logged as expected.
    private Stream<VersionApplication> toVersionApplicationOrSkip(AppDTO app) {
        Optional<VersionParser> parser = versionParsers.forApp(app.name());
        if (parser.isEmpty()) {
            logger.info("Skipping unconfigured app '{}' from scrape snapshot (removed from config)", app.name());
            return Stream.empty();
        }
        return Stream.of(new VersionApplication(
                app.name(),
                toSideObservation(app.name(), "current", app.currentValue(), app.currentLastSuccessAtEpochMillis(), app.currentLastFailureAtEpochMillis(), parser.get()),
                toSideObservation(app.name(), "latest", app.latestValue(), app.latestLastSuccessAtEpochMillis(), app.latestLastFailureAtEpochMillis(), parser.get())));
    }

    // A stored value can predate a config change (e.g. semver -> calver flip) or simply not match
    // the app's declared calver-format. Rehydrating it under the app's CONFIGURED parser can then
    // throw InvalidVersionException: that failure is isolated to this one (app, side) rather than
    // propagating and failing the whole snapshot read. The side degrades to value-less (value and
    // lastSuccessAt dropped) while lastFailureAt is preserved as stored regardless of parse outcome.
    private SideObservation toSideObservation(
            String appName, String side, String value, Long lastSuccessMillis, Long lastFailureMillis, VersionParser parser) {
        Optional<VersionValue> vv = Optional.empty();
        Optional<Instant> lastSuccess = Optional.empty();
        if (value != null) {
            try {
                vv = Optional.of(parser.parse(value));
                lastSuccess = lastSuccessMillis != null ? Optional.of(Instant.ofEpochMilli(lastSuccessMillis)) : Optional.empty();
            } catch (InvalidVersionException e) {
                logger.warn(
                        "Failed to parse stored {} value '{}' for app '{}' under configured scheme {}; "
                                + "degrading this side to value-less",
                        side, value, appName, parser.scheme(), e);
            }
        }
        Optional<Instant> lastFailure = lastFailureMillis != null ? Optional.of(Instant.ofEpochMilli(lastFailureMillis)) : Optional.empty();
        return new SideObservation(vv, lastSuccess, lastFailure);
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

    // Register the array type too: Jackson instantiates AppDTO[] reflectively while deserialising
    // the List<AppDTO>, so without the array registration the snapshot read throws in native and the
    // cache never populates (the bare record registration does not cover its array class).
    @RegisterForReflection(targets = {AppDTO.class, AppDTO[].class})
    private record AppDTO(
            String name,
            String currentValue,
            Long currentLastSuccessAtEpochMillis,
            Long currentLastFailureAtEpochMillis,
            String latestValue,
            Long latestLastSuccessAtEpochMillis,
            Long latestLastFailureAtEpochMillis) {
    }
}
