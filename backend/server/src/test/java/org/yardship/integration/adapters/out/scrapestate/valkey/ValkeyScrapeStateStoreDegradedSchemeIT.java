package org.yardship.integration.adapters.out.scrapestate.valkey;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yardship.core.domain.primitives.ScrapeSnapshot;
import org.yardship.core.domain.primitives.SemverVersion;
import org.yardship.core.domain.primitives.SideObservation;
import org.yardship.core.domain.primitives.VersionApplication;
import org.yardship.core.ports.out.ScrapeStateStore;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An app that is still CONFIGURED but has no usable {@code VersionParser} — because its
 * {@code version-scheme} carries an APP-scope config error — must be KEPT when the snapshot is read
 * back, not skipped (issue 09 / ADR-0032).
 *
 * <p>This is the read path that a regression guard caught silently dropping such an app from every
 * Surface, because it gated on parser presence rather than on whether the app was still configured.
 * Skipping is correct only for an entry whose app is genuinely gone from config — the case
 * {@code ValkeyScrapeStateStoreIT.read_snapshotWithAnUnconfiguredEntry_omitsIt_...} covers. The two
 * are easy to conflate and were conflated once, so each gets its own test.
 */
@QuarkusTest
@TestProfile(DegradedSchemeAppTestProfile.class)
class ValkeyScrapeStateStoreDegradedSchemeIT {

    @Inject
    ScrapeStateStore sut;

    @Inject
    RedisDataSource redisDataSource;

    @BeforeEach
    void clearSnapshot() {
        redisDataSource.key().del("scrape:snapshot");
    }

    @Test
    void read_configuredAppWithNoParser_isKeptValueLess_ratherThanDroppedLikeARemovedApp() {
        Instant successAt = Instant.parse("2026-07-01T10:00:00Z");
        Instant failureAt = Instant.parse("2026-07-01T10:04:00Z");
        Instant attemptAt = Instant.parse("2026-07-01T10:05:00Z");

        // A value persisted while the app was still healthy, plus a later failure stamp. write()
        // does not validate against config, so this stands in for "the ConfigMap was edited after
        // this entry was written".
        VersionApplication schemeBroken = new VersionApplication(
                "scheme-broken-app",
                new SideObservation(
                        Optional.of(new SemverVersion("1.0.0")),
                        Optional.of(successAt),
                        Optional.of(failureAt)),
                new SideObservation(
                        Optional.of(new SemverVersion("2.0.0")),
                        Optional.of(successAt),
                        Optional.of(failureAt)));

        sut.write(List.of(schemeBroken), attemptAt);

        Optional<ScrapeSnapshot> read = sut.read();

        assertTrue(read.isPresent(), "the snapshot must still be readable");
        List<VersionApplication> applications = read.get().applications();
        assertEquals(1, applications.size(),
                "a configured app with no parser must be KEPT, not skipped like a removed one: "
                        + applications);

        VersionApplication rehydrated = applications.get(0);
        assertEquals("scheme-broken-app", rehydrated.name());

        // No value is fabricated and no fallback parser invented: there is no parser to retype the
        // persisted string under, so both sides come back value-less and the app reads Unresolved,
        // with its reason supplied by the ConfigErrors projection rather than by the snapshot.
        assertTrue(rehydrated.current().value().isEmpty(),
                "no value may be fabricated for a side with no parser");
        assertTrue(rehydrated.latest().value().isEmpty(),
                "no value may be fabricated for a side with no parser");

        // lastSuccessAt goes with the value it described; claiming "last read succeeded at T" while
        // holding nothing read is a state no other path produces. lastFailureAt records an attempt
        // that genuinely happened, so it survives.
        assertTrue(rehydrated.current().lastSuccessAt().isEmpty(),
                "lastSuccessAt must be dropped alongside the value it described");
        assertEquals(Optional.of(failureAt), rehydrated.current().lastFailureAt(),
                "lastFailureAt records a real attempt and must be preserved");
        assertEquals(Optional.of(failureAt), rehydrated.latest().lastFailureAt(),
                "lastFailureAt records a real attempt and must be preserved");
    }
}
