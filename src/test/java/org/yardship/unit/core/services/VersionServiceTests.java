package org.yardship.unit.core.services;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.yardship.core.domain.primitives.Version;
import org.yardship.core.domain.primitives.VersionApplication;
import org.yardship.core.ports.out.VersionRepository;
import org.yardship.core.services.ApplicationVersionService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@QuarkusTest
public class VersionServiceTests {

    @InjectMock
    private VersionRepository versionRepository;

    @Inject
    private ApplicationVersionService sut;

    @Test
    void getListOfOldApplications_onlyReturnsOldVersionApplications() {
        // Arrange
        VersionApplication oldApplication = createOldApplication();
        VersionApplication up2DateApplication = createUp2DateApplication();
        when(versionRepository.getAllVersionApplications())
                .thenReturn(List.of(
                        oldApplication,
                        up2DateApplication)
                );

        // Act
        List<VersionApplication> oldApplications = sut.getListOfOldApplications();

        // Assert
        assertEquals(oldApplications.size(), 1);
        assertEquals(oldApplications.getFirst(), oldApplication);
    }

    private VersionApplication createOldApplication() {
        return new VersionApplication("Some-App", new Version("1.1.1"), new Version("2.2.2"));
    }

    private VersionApplication createUp2DateApplication() {
        return new VersionApplication("Another-app", new Version("2.2.2"), new Version("2.2.2"));
    }
}
