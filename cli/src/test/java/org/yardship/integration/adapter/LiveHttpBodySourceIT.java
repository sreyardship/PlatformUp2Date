package org.yardship.integration.adapter;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yardship.cli.adapter.LiveHttpBodySource;
import org.yardship.cli.port.BodySource;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Integration tests for the real {@link LiveHttpBodySource} adapter against a standalone WireMock
 * server, mirroring the style of the backend's {@code HttpRegexLatestSourceIT}. Uses port 8090 (the
 * backend suite already claims 8089) so both suites can run concurrently without a port clash.
 */
class LiveHttpBodySourceIT {

    static WireMockServer wireMockServer;

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(options().port(8090));
        wireMockServer.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMockServer.stop();
    }

    @BeforeEach
    void resetStubs() {
        wireMockServer.resetAll();
    }

    @Test
    void twoHundredResponse_returnsBodyVerbatim() {
        wireMockServer.stubFor(get(urlPathEqualTo("/body"))
                .willReturn(aResponse().withStatus(200).withBody("Version: 1.2.3")));

        LiveHttpBodySource source = new LiveHttpBodySource("http://localhost:8090/body");

        assertEquals("Version: 1.2.3", source.body());
    }

    @Test
    void nonTwoXxResponse_throwsBodyFetchException() {
        wireMockServer.stubFor(get(urlPathEqualTo("/body"))
                .willReturn(aResponse().withStatus(404).withBody("Not Found")));

        LiveHttpBodySource source = new LiveHttpBodySource("http://localhost:8090/body");

        assertThrows(BodySource.BodyFetchException.class, source::body,
                "a non-2xx response must translate to a fetch failure, not the raw body");
    }

    @Test
    void connectionError_throwsBodyFetchException() {
        // Port 8091 has no WireMock server listening on it in this test — a real connection error.
        LiveHttpBodySource source = new LiveHttpBodySource("http://localhost:8091/unreachable");

        assertThrows(BodySource.BodyFetchException.class, source::body,
                "a connection error must translate to a fetch failure, not propagate a raw IOException");
    }
}
