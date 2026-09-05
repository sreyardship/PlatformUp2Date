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
import org.yardship.core.ports.out.ApplicationSources;
import org.yardship.core.ports.out.ScrapeStateStore;
import org.yardship.core.ports.out.VersionSources;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
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
    private final Set<String> configuredAppNames;

    @Inject
    public ValkeyScrapeStateStore(
            RedisDataSource redisDataSource,
            ObjectMapper objectMapper,
            VersionParsers versionParsers,
            VersionSources versionSources) {
        this.values = redisDataSource.value(String.class, String.class);
        this.objectMapper = objectMapper;
        this.versionParsers = versionParsers;
        // The seam that tells "removed from config" apart from "still configured, but its
        // version-scheme carries an APP-scope config error": VersionSourceResolver registers one
        // ApplicationSources entry per NAMED configured app (issue 02 / ADR-0032), including
        // APP-scope-broken ones -- see its resolve()'s app-scope degrade path -- so this set is
        // exactly the set of apps a persisted entry may still legitimately belong to.
        this.configuredAppNames = versionSources.applicationSources().stream()
                .map(ApplicationSources::name)
                .collect(Collectors.toUnmodifiableSet());
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

    // A persisted entry with no parser now falls into exactly one of two cases, distinguished by
    // whether the app is still configured (VersionSources.applicationSources(), which registers one
    // entry per NAMED configured app INCLUDING APP-scope-broken ones -- see
    // VersionSourceResolver.resolve()'s app-scope degrade path), not by parser presence:
    //   - No longer configured: the entry is genuinely stale (e.g. removed from config), so it is
    //     skipped (not thrown) and logged as expected.
    //   - Still configured, but its version-scheme itself carries an APP-scope config error
    //     (ADR-0032): the app keeps its identity and is rehydrated as Unresolved on both sides --
    //     neither leg is parseable while that error stands -- rather than vanishing from every
    //     Surface. The APP-scope ConfigError already recorded elsewhere supplies the reason.
    private Stream<VersionApplication> toVersionApplicationOrSkip(AppDTO app) {
        if (!configuredAppNames.contains(app.name())) {
            logger.info("Skipping app '{}' from scrape snapshot: no longer configured", app.name());
            return Stream.empty();
        }
        Optional<VersionParser> parser = versionParsers.forApp(app.name());
        if (parser.isEmpty()) {
            logger.info("App '{}' is configured but has no version parser (its version-scheme "
                    + "carries an APP-scope config error); rehydrating as Unresolved", app.name());
            return Stream.of(new VersionApplication(
                    app.name(),
                    toDegradedSideObservation(app.currentLastFailureAtEpochMillis()),
                    toDegradedSideObservation(app.latestLastFailureAtEpochMillis())));
        }
        return Stream.of(new VersionApplication(
                app.name(),
                toSideObservation(app.name(), "current", app.currentValue(), app.currentLastSuccessAtEpochMillis(), app.currentLastFailureAtEpochMillis(), parser.get()),
                toSideObservation(app.name(), "latest", app.latestValue(), app.latestLastSuccessAtEpochMillis(), app.latestLastFailureAtEpochMillis(), parser.get())));
    }

    // Builds a value-less side for an app whose version-scheme itself has an APP-scope config
    // error: no parser exists to retype the persisted string under, so no value is fabricated and
    // no fallback parser is invented (ADR-0032). lastSuccessAt is dropped along with the value,
    // exactly as toSideObservation below does when it cannot produce one -- a side that reports
    // "last read succeeded at T" while holding nothing read is a state no other path in the system
    // produces, and it would read as neither resolved nor a failed refresh. lastFailureAt is
    // preserved as stored, since it records an attempt that genuinely happened.
    private SideObservation toDegradedSideObservation(Long lastFailureMillis) {
        Optional<Instant> lastFailure = lastFailureMillis != null ? Optional.of(Instant.ofEpochMilli(lastFailureMillis)) : Optional.empty();
        return new SideObservation(Optional.empty(), Optional.empty(), lastFailure);
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
