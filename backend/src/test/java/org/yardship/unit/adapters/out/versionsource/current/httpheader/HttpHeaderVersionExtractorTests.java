package org.yardship.unit.adapters.out.versionsource.current.httpheader;

import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.current.httpheader.HttpHeaderVersionExtractor;
import org.yardship.adapters.out.versionsource.http.HttpGetResponse;
import org.yardship.adapters.out.versionsource.regex.RegexVersionExtractor;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import org.yardship.core.domain.primitives.VersionValue;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link HttpHeaderVersionExtractor}, the {@code http-header} rule applied to the
 * final response the shared transport returns. Fed fake {@link HttpGetResponse}s — NO Arc, NO HTTP
 * — so status code and headers can be dictated per test.
 *
 * <p>Per {@code docs/adr/0030-http-header-current-source.md} — the binding specification for this
 * kind — its defining, load-bearing behavior is that it reads the configured header off the final
 * response <b>whatever its status code was</b>; the status is used only to compose a failure
 * message. That rule is now a plain extractor decision and is pinned here directly (a 403 carrying
 * the header still resolves); {@code HttpHeaderCurrentSourceIT} repeats it against a real server.
 */
class HttpHeaderVersionExtractorTests {

    private static final URI RESPONSE_URI = URI.create("http://localhost:8089/current");

    private static final VersionParser SEMVER_PARSER = new VersionParser(VersionScheme.SEMVER);
    private static final String EXTRACTOR_LABEL = "'http-header' current source";
    private static final String URL = "https://jenkins.example.com/";
    private static final String HEADER_NAME = "X-Jenkins";

    // --- the status code is ignored; only the header's presence matters (ADR-0030) --------------

    @Test
    void extract_readsTheHeader_offA403Response_ignoringTheStatusCode() {
        HttpGetResponse forbidden = response(403, Map.of("x-jenkins", List.of("2.568.2")), "<html>Forbidden</html>");

        VersionValue result = extractor(Optional.empty(), false).extract(forbidden);

        assertEquals("2.568.2", result.value(),
                "a secured Jenkins answers 403 and still volunteers its version; the read must not be gated on 2xx");
    }

    // --- No regex: the raw trimmed header value is parsed -------------------------------------

    @Test
    void extract_rawHeaderValue_isTrimmedAndParsed_whenNoRegexConfigured() {
        VersionValue result = extractor(Optional.empty(), false)
                .extract(response(200, Map.of(HEADER_NAME, List.of("  2.568.2  "))));

        assertEquals("2.568.2", result.value());
    }

    // --- regex: capture group 1 of the FIRST match, never the largest -------------------------

    @Test
    void extract_withRegex_takesCaptureGroup1OfTheFirstMatch_evenWhenALaterMatchIsLarger() {
        RegexVersionExtractor regex =
                new RegexVersionExtractor(EXTRACTOR_LABEL, "(\\d+\\.\\d+\\.\\d+)", SEMVER_PARSER);

        VersionValue result = extractor(Optional.of(regex), false)
                .extract(response(200, Map.of(HEADER_NAME, List.of("1.0.0 then later 9.9.9"))));

        assertEquals("1.0.0", result.value(),
                "firstIn must pick the FIRST match (1.0.0), never the largest (9.9.9)");
    }

    @Test
    void extract_withRegex_nginxUbuntuServerHeader_yields_1_25_3_NOT_22_04() {
        // The exact fixture from ADR-0030's "The first match, not the largest" section: a current
        // version is a single observation, not a selection, so http-header must never report 22.04.
        RegexVersionExtractor regex =
                new RegexVersionExtractor(EXTRACTOR_LABEL, "nginx/(\\d+\\.\\d+\\.\\d+)", SEMVER_PARSER);
        HttpHeaderVersionExtractor serverHeader = new HttpHeaderVersionExtractor(
                URL, "Server", Optional.of(regex), false, SEMVER_PARSER);

        VersionValue result = serverHeader.extract(response(200, Map.of("Server", List.of("nginx/1.25.3 (Ubuntu/22.04)"))));

        assertEquals("1.25.3", result.value());
        assertNotEquals("22.04", result.value());
    }

    // --- strip-prerelease -----------------------------------------------------------------------

    @Test
    void extract_withStripPrereleaseTrue_clearsThePrereleaseSegment() {
        VersionValue result = extractor(Optional.empty(), true)
                .extract(response(200, Map.of(HEADER_NAME, List.of("2.568.2-rc1"))));

        assertEquals("2.568.2", result.value());
    }

    @Test
    void extract_withStripPrereleaseFalse_preservesThePrereleaseSegment() {
        VersionValue result = extractor(Optional.empty(), false)
                .extract(response(200, Map.of(HEADER_NAME, List.of("2.568.2-rc1"))));

        assertTrue(result.value().contains("rc1"),
                "with strip-prerelease false, the prerelease segment must be preserved; was: " + result.value());
    }

    // --- header name matching: case-insensitive, repeated takes first -------------------------

    @Test
    void extract_matchesTheHeaderName_caseInsensitively() {
        VersionValue result = extractor(Optional.empty(), false)
                .extract(response(200, Map.of("x-jenkins", List.of("2.568.2"))));

        assertEquals("2.568.2", result.value());
    }

    @Test
    void extract_withARepeatedHeader_takesTheFirstValue() {
        VersionValue result = extractor(Optional.empty(), false)
                .extract(response(200, Map.of(HEADER_NAME, List.of("2.568.2", "9.9.9"))));

        assertEquals("2.568.2", result.value());
    }

    // --- the three distinct failure messages ---------------------------------------------------

    @Test
    void extract_throws_whenHeaderIsAbsent_namingTheHeader_theObservedStatus_andTheUrl() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> extractor(Optional.empty(), false).extract(response(403, Map.of())));

        assertTrue(ex.getMessage().contains(HEADER_NAME), "must name the header; was: " + ex.getMessage());
        assertTrue(ex.getMessage().toLowerCase().contains("absent"),
                "must say the header was absent; was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("403"), "must name the observed status; was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains(URL), "must name the url; was: " + ex.getMessage());
    }

    @Test
    void extract_throws_whenHeaderIsPresentButEmptyAfterTrimming_namingTheHeader_theObservedStatus_andTheUrl() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> extractor(Optional.empty(), false)
                        .extract(response(200, Map.of(HEADER_NAME, List.of("   ")))));

        assertTrue(ex.getMessage().contains(HEADER_NAME), "must name the header; was: " + ex.getMessage());
        assertTrue(ex.getMessage().toLowerCase().contains("empty"),
                "must say the header value was empty; was: " + ex.getMessage());
        assertFalse(ex.getMessage().toLowerCase().contains("absent"),
                "a present-but-empty header must NEVER be worded as missing/absent; was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("200"), "must name the observed status; was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains(URL), "must name the url; was: " + ex.getMessage());
    }

    @Test
    void extract_throws_whenTheValueDoesNotParse_carryingTheActualValueFound() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> extractor(Optional.empty(), false)
                        .extract(response(200, Map.of(HEADER_NAME, List.of("not-a-version")))));

        assertTrue(ex.getMessage().contains(HEADER_NAME), "must name the header; was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("not-a-version"),
                "must carry the actual (unparseable) value found; was: " + ex.getMessage());
    }

    @Test
    void extract_throws_whenRegexMatchesNothing_carryingTheActualValueFound() {
        RegexVersionExtractor regex =
                new RegexVersionExtractor(EXTRACTOR_LABEL, "(\\d+\\.\\d+\\.\\d+)", SEMVER_PARSER);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> extractor(Optional.of(regex), false)
                        .extract(response(200, Map.of(HEADER_NAME, List.of("no digits in here")))));

        assertTrue(ex.getMessage().contains(HEADER_NAME), "must name the header; was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("no digits in here"),
                "must carry the actual value the regex failed to match a version in; was: " + ex.getMessage());
    }

    @Test
    void theThreeFailureMessages_areAllDistinctFromEachOther() {
        String absent = failureMessage(response(403, Map.of()));
        String presentButEmpty = failureMessage(response(200, Map.of(HEADER_NAME, List.of("  "))));
        String unparseable = failureMessage(response(200, Map.of(HEADER_NAME, List.of("garbage"))));

        assertNotEquals(absent, presentButEmpty,
                "absent and present-but-empty are different facts and must read differently");
        assertNotEquals(absent, unparseable,
                "absent and unparseable are different facts and must read differently");
        assertNotEquals(presentButEmpty, unparseable,
                "present-but-empty and unparseable are different facts and must read differently");
    }

    private static String failureMessage(HttpGetResponse response) {
        HttpHeaderVersionExtractor extractor = extractor(Optional.empty(), false);
        return assertThrows(IllegalStateException.class, () -> extractor.extract(response)).getMessage();
    }

    private static HttpHeaderVersionExtractor extractor(
            Optional<RegexVersionExtractor> regex, boolean stripPrerelease) {
        return new HttpHeaderVersionExtractor(URL, HEADER_NAME, regex, stripPrerelease, SEMVER_PARSER);
    }

    private static HttpGetResponse response(int statusCode, Map<String, List<String>> headers) {
        return response(statusCode, headers, "");
    }

    private static HttpGetResponse response(int statusCode, Map<String, List<String>> headers, String body) {
        return new HttpGetResponse(RESPONSE_URI, statusCode, headers, body);
    }
}
