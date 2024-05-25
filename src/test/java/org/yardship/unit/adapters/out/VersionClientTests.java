package org.yardship.unit.adapters.out;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionclient.InvalidVersionResponseException;
import org.yardship.adapters.out.versionclient.VersionClient;
import org.yardship.adapters.out.versionclient.VersionResponseDTO;
import org.yardship.adapters.out.versionclient.YardshipClient;
import org.yardship.core.domain.primitives.Version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@QuarkusTest
public class VersionClientTests {

    @InjectMock
    @RestClient
    private YardshipClient mockYardshipClient;

    @Inject
    private VersionClient sut;

    @Test
    void getCurrentVersion_returnsExpectedVersion_whenResponseIsValid() {
        // Arrange
        String expectedVersion = "1.23.4";
        when(mockYardshipClient.getApiVersion())
            .thenReturn(new VersionResponseDTO(expectedVersion));

        // Act
        Version actualVersion = sut.getCurrentVersion();

        // Assert
        assertEquals(expectedVersion, actualVersion.toString());
    }

    @Test
    void getCurrentVersion_throwsInvalidVersionException_whenResponseIsNull() {
        // Arrange
        when(mockYardshipClient.getApiVersion())
            .thenReturn(null);

        // Act & Assert
        assertThrows(InvalidVersionResponseException.class,
            () -> sut.getCurrentVersion());
    }
}
