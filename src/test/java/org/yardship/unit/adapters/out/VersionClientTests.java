package org.yardship.unit.adapters.out;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionclient.VersionClient;
import org.yardship.core.domain.primitives.Version;

@QuarkusTest
public class VersionClientTests {

    @Inject
    private VersionClient sut;

    @Test
    void dummy() {
        Version result = sut.getCurrentVersion();

        Assertions.assertEquals(result.value(), "v2.10.7+b060053");
    }
}
