package org.yardship.unit.domain.services;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.yardship.core.services.VersionService;

@QuarkusTest
public class VersionServiceTests {

    @Inject
    private VersionService sut;

    @Test
    void dummy() {
        String resultString = sut.getCurrentVersion().value();

        Assertions.assertEquals(resultString, "v2.10.7+b060053");
    }
}
