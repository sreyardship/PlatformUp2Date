package org.yardship.unit.adapters.out.versionsource.current.httpheader;

import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.current.httpheader.HttpHeaderCurrentSource;
import org.yardship.adapters.out.versionsource.current.httpheader.HttpHeaderFetch;
import org.yardship.adapters.out.versionsource.current.httpheader.HttpHeaderResponse;
import org.yardship.adapters.out.versionsource.regex.RegexVersionExtractor;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import org.yardship.core.domain.primitives.VersionValue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link HttpHeaderCurrentSource} as a pure POJO — NO Arc, NO real HTTP. It is
 * constructed directly with a fake {@link HttpHeaderFetch} (the narrow seam this slice introduces,
 * mirroring how {@code HttpJsonCurrentSourceTests} fakes {@code HttpJsonCurrentVersionClient}), so status
 * code and headers can be dictated per test without a stub server.
 *
 * <p>Per {@code docs/adr/0030-http-header-current-source.md} — the binding specification for this
 * slice — this source's defining, load-bearing behavior is that it reads the configured header off
 * the final response <b>whatever its status code was</b>; the status is used only to compose a
 * failure message. That specific regression guard (a 403 carrying the header still resolves) is
 * covered at the integration level against a real stub server ({@code HttpHeaderCurrentSourceIT}),
 * since it is fundamentally a wire-level fact; this class covers every other behavior — extraction,
 * trimming, case-insensitive/repeated-header matching, and the three distinct failure messages —
 * as fast unit tests.
 */
class HttpHeaderCurrentSourceTests {

    private static final VersionParser SEMVER_PARSER = new VersionParser(VersionScheme.SEMVER);
    private static final String EXTRACTOR_LABEL = "'http-header' current source";
    private static final String URL = "https://jenkins.example.com/";
    private static final String HEADER_NAME = "X-Jenkins";

    // --- No regex: the raw trimmed header value is parsed -------------------------------------

    @Test
    void version_rawHeaderValue_isTrimmedAndParsed_whenNoRegexConfigured() {
        HttpHeaderCurrentSource source = source(
                response(200, Map.of(HEADER_NAME, List.of("  2.568.2  "))), Optional.empty(), false);

        VersionValue result = source.version();

        assertEquals("2.568.2", result.value());
    }

    // --- regex: capture group 1 of the FIRST match, never the largest -------------------------

    @Test
    void version_withRegex_takesCaptureGroup1OfTheFirstMatch_evenWhenALaterMatchIsLarger() {
        RegexVersionExtractor extractor =
                new RegexVersionExtractor(EXTRACTOR_LABEL, "(\\d+\\.\\d+\\.\\d+)", SEMVER_PARSER);
        HttpHeaderCurrentSource source = source(
                response(200, Map.of(HEADER_NAME, List.of("1.0.0 then later 9.9.9"))),
                Optional.of(extractor), false);

        VersionValue result = source.version();

        assertEquals("1.0.0", result.value(),
                "firstIn must pick the FIRST match (1.0.0), never the largest (9.9.9)");
    }

    @Test
    void version_withRegex_nginxUbuntuServerHeader_yields_1_25_3_NOT_22_04() {
        // The exact fixture from ADR-0030's "The first match, not the largest" section: a current
        // version is a single observation, not a selection, so http-header must never report 22.04.
        RegexVersionExtractor extractor =
                new RegexVersionExtractor(EXTRACTOR_LABEL, "nginx/(\\d+\\.\\d+\\.\\d+)", SEMVER_PARSER);
        HttpHeaderResponse response = response(200, Map.of("Server", List.of("nginx/1.25.3 (Ubuntu/22.04)")));
        HttpHeaderCurrentSource serverHeaderSource = new HttpHeaderCurrentSource(
                () -> response, URL, "Server", Optional.of(extractor), false, SEMVER_PARSER);

        VersionValue result = serverHeaderSource.version();

        assertEquals("1.25.3", result.value());
        assertNotEquals("22.04", result.value());
    }

    // --- strip-prerelease -----------------------------------------------------------------------

    @Test
    void version_withStripPrereleaseTrue_clearsThePrereleaseSegment() {
        HttpHeaderCurrentSource source = source(
                response(200, Map.of(HEADER_NAME, List.of("2.568.2-rc1"))), Optional.empty(), true);

        VersionValue result = source.version();

        assertEquals("2.568.2", result.value());
    }

    @Test
    void version_withStripPrereleaseFalse_preservesThePrereleaseSegment() {
        HttpHeaderCurrentSource source = source(
                response(200, Map.of(HEADER_NAME, List.of("2.568.2-rc1"))), Optional.empty(), false);

        VersionValue result = source.version();

        assertTrue(result.value().contains("rc1"),
                "with strip-prerelease false, the prerelease segment must be preserved; was: " + result.value());
    }

    // --- header name matching: case-insensitive, repeated takes first -------------------------

    @Test
    void version_matchesTheHeaderName_caseInsensitively() {
        // The wire carried lowercase 'x-jenkins'; configured as 'X-Jenkins' — RFC 9110 §5.1.
        HttpHeaderCurrentSource source = source(
                response(200, Map.of("x-jenkins", List.of("2.568.2"))), Optional.empty(), false);

        VersionValue result = source.version();

        assertEquals("2.568.2", result.value());
    }

    @Test
    void version_withARepeatedHeader_takesTheFirstValue() {
        HttpHeaderCurrentSource source = source(
                response(200, Map.of(HEADER_NAME, List.of("2.568.2", "9.9.9"))), Optional.empty(), false);

        VersionValue result = source.version();

        assertEquals("2.568.2", result.value());
    }

    // --- the three distinct failure messages ---------------------------------------------------

    @Test
    void version_throws_whenHeaderIsAbsent_namingTheHeader_theObservedStatus_andTheUrl() {
        HttpHeaderCurrentSource source = source(response(403, Map.of()), Optional.empty(), false);

        IllegalStateException ex = assertThrows(IllegalStateException.class, source::version);

        assertTrue(ex.getMessage().contains(HEADER_NAME),
                "must name the header; was: " + ex.getMessage());
        assertTrue(ex.getMessage().toLowerCase().contains("absent"),
                "must say the header was absent; was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("403"),
                "must name the observed status; was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains(URL),
                "must name the url; was: " + ex.getMessage());
    }

    @Test
    void version_throws_whenHeaderIsPresentButEmptyAfterTrimming_namingTheHeader_theObservedStatus_andTheUrl() {
        HttpHeaderCurrentSource source = source(
                response(200, Map.of(HEADER_NAME, List.of("   "))), Optional.empty(), false);

        IllegalStateException ex = assertThrows(IllegalStateException.class, source::version);

        assertTrue(ex.getMessage().contains(HEADER_NAME),
                "must name the header; was: " + ex.getMessage());
        assertTrue(ex.getMessage().toLowerCase().contains("empty"),
                "must say the header value was empty; was: " + ex.getMessage());
        assertFalse(ex.getMessage().toLowerCase().contains("absent"),
                "a present-but-empty header must NEVER be worded as missing/absent; was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("200"),
                "must name the observed status; was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains(URL),
                "must name the url; was: " + ex.getMessage());
    }

    @Test
    void version_throws_whenTheValueDoesNotParse_carryingTheActualValueFound() {
        HttpHeaderCurrentSource source = source(
                response(200, Map.of(HEADER_NAME, List.of("not-a-version"))), Optional.empty(), false);

        IllegalStateException ex = assertThrows(IllegalStateException.class, source::version);

        assertTrue(ex.getMessage().contains(HEADER_NAME),
                "must name the header; was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("not-a-version"),
                "must carry the actual (unparseable) value found; was: " + ex.getMessage());
    }

    @Test
    void version_throws_whenRegexMatchesNothing_carryingTheActualValueFound() {
        RegexVersionExtractor extractor =
                new RegexVersionExtractor(EXTRACTOR_LABEL, "(\\d+\\.\\d+\\.\\d+)", SEMVER_PARSER);
        HttpHeaderCurrentSource source = source(
                response(200, Map.of(HEADER_NAME, List.of("no digits in here"))),
                Optional.of(extractor), false);

        IllegalStateException ex = assertThrows(IllegalStateException.class, source::version);

        assertTrue(ex.getMessage().contains(HEADER_NAME),
                "must name the header; was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("no digits in here"),
                "must carry the actual value the regex failed to match a version in; was: " + ex.getMessage());
    }

    @Test
    void theThreeFailureMessages_areAllDistinctFromEachOther() {
        String absent = failureMessage(response(403, Map.of()), Optional.empty());
        String presentButEmpty = failureMessage(response(200, Map.of(HEADER_NAME, List.of("  "))), Optional.empty());
        String unparseable = failureMessage(response(200, Map.of(HEADER_NAME, List.of("garbage"))), Optional.empty());

        assertNotEquals(absent, presentButEmpty,
                "absent and present-but-empty are different facts and must read differently");
        assertNotEquals(absent, unparseable,
                "absent and unparseable are different facts and must read differently");
        assertNotEquals(presentButEmpty, unparseable,
                "present-but-empty and unparseable are different facts and must read differently");
    }

    private static String failureMessage(HttpHeaderResponse response, Optional<RegexVersionExtractor> extractor) {
        HttpHeaderCurrentSource source = source(response, extractor, false);
        return assertThrows(IllegalStateException.class, source::version).getMessage();
    }

    private static HttpHeaderCurrentSource source(
            HttpHeaderResponse response, Optional<RegexVersionExtractor> extractor, boolean stripPrerelease) {
        return new HttpHeaderCurrentSource(
                () -> response, URL, HEADER_NAME, extractor, stripPrerelease, SEMVER_PARSER);
    }

    private static HttpHeaderResponse response(int statusCode, Map<String, List<String>> headers) {
        return new HttpHeaderResponse() {
            @Override
            public int statusCode() {
                return statusCode;
            }

            @Override
            public Map<String, List<String>> headers() {
                return headers;
            }
        };
    }
}
