package org.yardship.unit.core.services;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.yardship.core.domain.primitives.Version;
import org.yardship.core.domain.primitives.VersionApplication;
import org.yardship.core.ports.out.VersionRepository;
import org.yardship.core.services.ApplicationVersionService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@QuarkusTest
public class VersionServiceTests {

    @InjectMock
    private VersionRepository versionRepository;

    @Inject
    private ApplicationVersionService sut;

    @Test
    void getApplications_returnsCachedVersionApplications() {
        // Arrange
        VersionApplication oldApplication = createOldApplication();
        VersionApplication up2DateApplication = createUp2DateApplication();
        when(versionRepository.getAllVersionApplications())
                .thenReturn(List.of(oldApplication, up2DateApplication));

        // Act
        sut.scrape();
        List<VersionApplication> result = sut.getApplications();

        // Assert
        assertEquals(2, result.size());
        assertEquals(oldApplication, result.getFirst());
        assertEquals(up2DateApplication, result.getLast());
    }

    @Test
    void getApplications_returnsEmptyList_beforeFirstScrape() {
        // Arrange — no scrape() called

        // Act
        List<VersionApplication> result = sut.getApplications();

        // Assert
        assertTrue(result.isEmpty());
    }

    @Test
    void scrape_keepsOldCache_whenRepositoryThrows() {
        // Arrange
        VersionApplication oldApplication = createOldApplication();
        VersionApplication up2DateApplication = createUp2DateApplication();
        List<VersionApplication> expectedApplications = List.of(oldApplication, up2DateApplication);

        when(versionRepository.getAllVersionApplications())
                .thenReturn(expectedApplications);
        sut.scrape();

        when(versionRepository.getAllVersionApplications())
                .thenThrow(new RuntimeException("connection failed"));

        // Act
        sut.scrape();
        List<VersionApplication> result = sut.getApplications();

        // Assert
        assertEquals(2, result.size());
        assertEquals(oldApplication, result.getFirst());
        assertEquals(up2DateApplication, result.getLast());
    }

    private VersionApplication createOldApplication() {
        return new VersionApplication("Some-App", new Version("1.1.1"), new Version("2.2.2"));
    }

    private VersionApplication createUp2DateApplication() {
        return new VersionApplication("Another-app", new Version("2.2.2"), new Version("2.2.2"));
    }
}
