package org.yardship.integration.adapter;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yardship.confcheck.adapter.LiveHttpResponseSource;
import org.yardship.confcheck.port.BodySource;
import org.yardship.confcheck.port.ResponseSource;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the real {@link LiveHttpResponseSource} adapter against a standalone
 * WireMock server, mirroring {@code LiveHttpBodySourceIT}'s style and redirect coverage. Uses port
 * 8094 — 8089 (backend), 8090/8093 (this module's own {@code LiveHttpBodySourceIT}/
 * {@code RegexCommandWiringTests}) are already claimed within this module's suite.
 *
 * <p>The single most important assertion in this class — and in this whole slice — is
 * {@link #nonTwoXxResponse_isReturned_notThrown()}: unlike its {@link BodySource} sibling
 * ({@code LiveHttpBodySource}), which raises {@link BodySource.BodyFetchException} on any non-2xx
 * response, {@link LiveHttpResponseSource} must RETURN a non-2xx response so the {@code header}
 * surface can read a header off it (a secured Jenkins answering 403 while still volunteering its
 * version in {@code X-Jenkins} is the motivating case — see
 * {@code docs/adr/0030-http-header-current-source.md}).
 */
class LiveHttpResponseSourceIT {

    static WireMockServer wireMockServer;

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(options().port(8094));
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
    void twoHundredResponse_returnsStatusCodeAndHeaders() {
        wireMockServer.stubFor(get(urlPathEqualTo("/version"))
                .willReturn(aResponse().withStatus(200).withHeader("X-Jenkins", "2.568.2")));

        LiveHttpResponseSource source = new LiveHttpResponseSource("http://localhost:8094/version");

        ResponseSource.Response response = source.fetch();

        assertEquals(200, response.statusCode());
        assertEquals("2.568.2", response.firstHeader("X-Jenkins").orElseThrow());
    }

    /**
     * THE load-bearing assertion of this slice: a non-2xx final response must be RETURNED, not
     * thrown, and its header must still be readable — the inverse of
     * {@code LiveHttpBodySourceIT#nonTwoXxResponse_throwsBodyFetchException}.
     */
    @Test
    void nonTwoXxResponse_isReturned_notThrown() {
        wireMockServer.stubFor(get(urlPathEqualTo("/version"))
                .willReturn(aResponse().withStatus(403).withHeader("X-Jenkins", "2.568.2")));

        LiveHttpResponseSource source = new LiveHttpResponseSource("http://localhost:8094/version");

        ResponseSource.Response response = source.fetch();

        assertEquals(403, response.statusCode(),
                "a 403 must be RETURNED as a response, not translated into a thrown exception");
        assertEquals("2.568.2", response.firstHeader("X-Jenkins").orElseThrow(),
                "the header must still be readable off a non-2xx response — this is the whole point "
                        + "of ResponseSource existing as a sibling of BodySource");
    }

    @Test
    void firstHeader_lookupIsCaseInsensitive() {
        // The wire carries lowercase 'x-jenkins'; looked up as 'X-Jenkins' (RFC 9110 section 5.1).
        wireMockServer.stubFor(get(urlPathEqualTo("/version"))
                .willReturn(aResponse().withStatus(200).withHeader("x-jenkins", "2.568.2")));

        LiveHttpResponseSource source = new LiveHttpResponseSource("http://localhost:8094/version");

        ResponseSource.Response response = source.fetch();

        assertEquals("2.568.2", response.firstHeader("X-Jenkins").orElseThrow());
    }

    @Test
    void firstHeader_repeatedHeader_returnsFirstValue() {
        wireMockServer.stubFor(get(urlPathEqualTo("/version"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("X-Jenkins", "2.568.2", "9.9.9")));

        LiveHttpResponseSource source = new LiveHttpResponseSource("http://localhost:8094/version");

        ResponseSource.Response response = source.fetch();

        assertEquals("2.568.2", response.firstHeader("X-Jenkins").orElseThrow());
    }

    @Test
    void firstHeader_absentHeader_returnsEmpty() {
        wireMockServer.stubFor(get(urlPathEqualTo("/version"))
                .willReturn(aResponse().withStatus(200)));

        LiveHttpResponseSource source = new LiveHttpResponseSource("http://localhost:8094/version");

        ResponseSource.Response response = source.fetch();

        assertTrue(response.firstHeader("X-Jenkins").isEmpty());
    }

    // --- Redirect handling (ADR-0029): headers must be read off the FINAL response --------------

    @Test
    void redirect301_headersReadOffTheFinalResponse_notTheIntermediateOne() {
        wireMockServer.stubFor(get(urlPathEqualTo("/version"))
                .willReturn(aResponse()
                        .withStatus(301)
                        .withHeader("Location", "/version-final")
                        // Deliberately different, so a wrongly-used intermediate response is caught.
                        .withHeader("X-Jenkins", "0.0.1")));
        wireMockServer.stubFor(get(urlPathEqualTo("/version-final"))
                .willReturn(aResponse().withStatus(200).withHeader("X-Jenkins", "2.568.2")));

        LiveHttpResponseSource source = new LiveHttpResponseSource("http://localhost:8094/version");

        ResponseSource.Response response = source.fetch();

        assertEquals(200, response.statusCode());
        assertEquals("2.568.2", response.firstHeader("X-Jenkins").orElseThrow(),
                "the header must come from the FINAL response reached after following the redirect, "
                        + "not the intermediate 301's own headers");
    }

    @Test
    void redirect_toANonTwoXxFinalResponse_isStillReturned_withItsHeaders() {
        wireMockServer.stubFor(get(urlPathEqualTo("/version"))
                .willReturn(aResponse()
                        .withStatus(302)
                        .withHeader("Location", "/version-secured")));
        wireMockServer.stubFor(get(urlPathEqualTo("/version-secured"))
                .willReturn(aResponse().withStatus(403).withHeader("X-Jenkins", "2.568.2")));

        LiveHttpResponseSource source = new LiveHttpResponseSource("http://localhost:8094/version");

        ResponseSource.Response response = source.fetch();

        assertEquals(403, response.statusCode());
        assertEquals("2.568.2", response.firstHeader("X-Jenkins").orElseThrow());
    }

    // --- Genuine transport failure -----------------------------------------------------------

    @Test
    void connectionError_throwsBodyFetchException() {
        // No WireMock server listens on 8095 in this test — a real connection error, distinct
        // from every non-2xx case above, which must all be returned rather than thrown.
        LiveHttpResponseSource source = new LiveHttpResponseSource("http://localhost:8095/unreachable");

        assertThrows(BodySource.BodyFetchException.class, source::fetch,
                "a genuine transport failure (connection refused) must still translate to "
                        + "BodyFetchException — only a non-2xx STATUS is exempted from this, not a real "
                        + "transport error");
    }
}
