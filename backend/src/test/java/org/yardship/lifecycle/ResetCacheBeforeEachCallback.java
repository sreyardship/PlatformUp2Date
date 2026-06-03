package org.yardship.lifecycle;

import io.quarkus.test.junit.callback.QuarkusTestBeforeEachCallback;
import io.quarkus.test.junit.callback.QuarkusTestMethodContext;
import jakarta.enterprise.inject.spi.CDI;
import org.yardship.core.services.ApplicationVersionService;

public class ResetCacheBeforeEachCallback implements QuarkusTestBeforeEachCallback {

    @Override
    public void beforeEach(QuarkusTestMethodContext context) {
        try {
            ApplicationVersionService service = CDI.current()
                    .select(ApplicationVersionService.class)
                    .get();
            service.resetCache();
        } catch (Exception ignored) {
            // Service may not be available in all test classes
        }
    }
}
