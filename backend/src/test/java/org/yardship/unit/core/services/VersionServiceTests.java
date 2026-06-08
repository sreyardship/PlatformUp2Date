package org.yardship.unit.core.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yardship.core.domain.primitives.Version;
import org.yardship.core.domain.primitives.VersionApplication;
import org.yardship.core.ports.out.VersionRepository;
import org.yardship.core.services.ApplicationVersionService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VersionServiceTests {

    private static final Duration SCRAPE_INTERVAL = Duration.ofHours(1);

    private VersionRepository versionRepository;
    private MutableClock clock;
    private ApplicationVersionService sut;

    @BeforeEach
    void setUp() {
        versionRepository = mock(VersionRepository.class);
        clock = new MutableClock(Instant.parse("2026-06-08T00:00:00Z"));
        sut = new ApplicationVersionService(versionRepository, SCRAPE_INTERVAL, clock);
    }

    @Test
    void getApplications_returnsEmptyList_whenRepositoryHasNoApplications() {
        when(versionRepository.getAllVersionApplications()).thenReturn(List.of());

        List<VersionApplication> result = sut.getApplications();

        assertTrue(result.isEmpty());
    }

    @Test
    void getApplications_refreshesOnFirstCall() {
        VersionApplication oldApplication = createOldApplication();
        VersionApplication up2DateApplication = createUp2DateApplication();
        when(versionRepository.getAllVersionApplications())
                .thenReturn(List.of(oldApplication, up2DateApplication));

        List<VersionApplication> result = sut.getApplications();

        assertEquals(2, result.size());
        assertEquals(oldApplication, result.getFirst());
        assertEquals(up2DateApplication, result.getLast());
        verify(versionRepository, times(1)).getAllVersionApplications();
    }

    @Test
    void getApplications_doesNotRefresh_whenIntervalHasNotElapsed() {
        when(versionRepository.getAllVersionApplications())
                .thenReturn(List.of(createOldApplication()));
        sut.getApplications();

        // Just shy of the interval — the cache must be served without a refresh.
        clock.advance(SCRAPE_INTERVAL.minusSeconds(1));
        sut.getApplications();

        verify(versionRepository, times(1)).getAllVersionApplications();
    }

    @Test
    void getApplications_refreshes_whenIntervalHasElapsed() {
        when(versionRepository.getAllVersionApplications())
                .thenReturn(List.of(createOldApplication()));
        sut.getApplications();

        clock.advance(SCRAPE_INTERVAL);
        sut.getApplications();

        verify(versionRepository, times(2)).getAllVersionApplications();
    }

    @Test
    void getApplications_keepsOldCache_whenRefreshFails() {
        VersionApplication oldApplication = createOldApplication();
        VersionApplication up2DateApplication = createUp2DateApplication();
        when(versionRepository.getAllVersionApplications())
                .thenReturn(List.of(oldApplication, up2DateApplication));
        sut.getApplications();

        when(versionRepository.getAllVersionApplications())
                .thenThrow(new RuntimeException("connection failed"));
        clock.advance(SCRAPE_INTERVAL);
        List<VersionApplication> result = sut.getApplications();

        assertEquals(2, result.size());
        assertEquals(oldApplication, result.getFirst());
        assertEquals(up2DateApplication, result.getLast());
    }

    @Test
    void getApplications_doesNotRetryFailedRefresh_untilIntervalElapsesAgain() {
        when(versionRepository.getAllVersionApplications())
                .thenThrow(new RuntimeException("connection failed"));

        // First call attempts (and fails) — a second call within the interval must
        // not hammer the failing dependency.
        sut.getApplications();
        clock.advance(SCRAPE_INTERVAL.minusSeconds(1));
        sut.getApplications();

        verify(versionRepository, times(1)).getAllVersionApplications();
    }

    private VersionApplication createOldApplication() {
        return new VersionApplication("Some-App", new Version("1.1.1"), new Version("2.2.2"));
    }

    private VersionApplication createUp2DateApplication() {
        return new VersionApplication("Another-app", new Version("2.2.2"), new Version("2.2.2"));
    }

    /** A {@link Clock} whose instant can be advanced by hand to drive staleness checks. */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        private void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
