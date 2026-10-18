package org.yardship.unit.adapters.out.versionsource.http;

import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.http.HttpTransport;
import org.yardship.adapters.out.versionsource.http.HttpTransportConfig;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the construction boundary of {@link HttpTransport}. What the built transport does
 * on the wire — redirects, credential rules, TLS, auth rendering — is proven once against real
 * servers in {@code HttpTransportIT}.
 */
class HttpTransportTests {

    @Test
    void from_aResolvedConfig_buildsATransportForTheUrl() {
        HttpTransportConfig.Resolution resolved =
                new HttpTransportConfig.Resolution(Optional.empty(), Optional.empty(), Optional.empty(), false);

        assertNotNull(HttpTransport.from("https://app.example.com/version", resolved));
    }

    @Test
    void from_aFailedResolution_throws_namingTheFailure() {
        // A failed resolution must have been mapped to a FailedCurrentSource by the factory already;
        // reaching the transport with one is a defect, not a config error to degrade quietly.
        HttpTransportConfig.Resolution failed = new HttpTransportConfig.Resolution(
                Optional.of("The 'http-json' current source's 'ca-cert' is configured but blank"),
                Optional.empty(), Optional.empty(), false);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> HttpTransport.from("https://app.example.com/version", failed));

        assertTrue(ex.getMessage().contains("'ca-cert' is configured but blank"),
                "must carry the resolution's failure message; was: " + ex.getMessage());
    }
}
