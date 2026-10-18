package org.yardship.unit.adapters.out.versionsource.http;

import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.http.HttpGetResponse;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link HttpGetResponse}, the final-response view every extractor reads: the
 * 2xx test the {@code http-json} and {@code http-prometheus} extractors gate on, and the
 * case-insensitive, first-value header lookup the {@code http-header} extractor reads with, and the
 * bounded body copy a failure message quotes.
 */
class HttpGetResponseTests {

    private static final URI RESPONSE_URI = URI.create("http://localhost:8089/current");

    @Test
    void isSuccess_isTrueForEvery2xx_andFalseOtherwise() {
        assertTrue(response(200).isSuccess());
        assertTrue(response(204).isSuccess());
        assertFalse(response(199).isSuccess());
        assertFalse(response(301).isSuccess());
        assertFalse(response(403).isSuccess());
        assertFalse(response(500).isSuccess());
    }

    @Test
    void firstHeader_matchesTheNameCaseInsensitively() {
        // The wire carried lowercase 'x-jenkins'; looked up as 'X-Jenkins' — RFC 9110 §5.1.
        HttpGetResponse response = response(200, Map.of("x-jenkins", List.of("2.568.2")));

        assertEquals(Optional.of("2.568.2"), response.firstHeader("X-Jenkins"));
    }

    @Test
    void firstHeader_takesTheFirstValue_ofARepeatedHeader() {
        HttpGetResponse response = response(200, Map.of("X-Jenkins", List.of("2.568.2", "9.9.9")));

        assertEquals(Optional.of("2.568.2"), response.firstHeader("X-Jenkins"));
    }

    @Test
    void firstHeader_isEmpty_whenTheHeaderIsAbsent() {
        HttpGetResponse response = response(200, Map.of("Server", List.of("nginx")));

        assertEquals(Optional.empty(), response.firstHeader("X-Jenkins"));
    }

    @Test
    void firstHeader_isEmpty_whenTheHeaderIsPresentWithNoValues() {
        HttpGetResponse response = response(200, Map.of("X-Jenkins", List.of()));

        assertEquals(Optional.empty(), response.firstHeader("X-Jenkins"));
    }

    @Test
    void truncatedBody_returnsAShortBodyWhole() {
        assertEquals("{\"message\":\"forbidden\"}",
                new HttpGetResponse(RESPONSE_URI, 403, Map.of(), "{\"message\":\"forbidden\"}").truncatedBody());
    }

    @Test
    void truncatedBody_boundsALongBody_andSaysSo() {
        String truncated = new HttpGetResponse(RESPONSE_URI, 500, Map.of(), "x".repeat(2000)).truncatedBody();

        assertTrue(truncated.endsWith("…[truncated]"), "was: " + truncated);
        assertTrue(truncated.length() < 600, "was " + truncated.length() + " chars");
    }

    @Test
    void truncatedBody_namesANullBody() {
        assertEquals("null", new HttpGetResponse(RESPONSE_URI, 204, Map.of(), null).truncatedBody());
    }

    private static HttpGetResponse response(int status) {
        return response(status, Map.of());
    }

    private static HttpGetResponse response(int status, Map<String, List<String>> headers) {
        return new HttpGetResponse(RESPONSE_URI, status, headers, "");
    }
}
