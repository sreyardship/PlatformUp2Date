package org.yardship.unit.core.services;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionclient.VersionClient;
import org.yardship.core.domain.primitives.Version;
import org.yardship.core.services.VersionService;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@QuarkusTest
public class VersionServiceTests {

    @InjectMock
    private VersionClient client;

    @Inject
    private VersionService sut;

    @Test
    void isVersionOld_returnsTrue_whenCurrentVersionIsOld() {
        // Arrange
        Version oldVersion = new Version("1.2.3");
        Version newVersion = new Version("3.2.1");
        when(client.getCurrentVersion()).thenReturn(oldVersion);
        when(client.getLatestVersion()).thenReturn(newVersion);

        // Act & Assert
        assertTrue(sut.isVersionOld());
    }
}
