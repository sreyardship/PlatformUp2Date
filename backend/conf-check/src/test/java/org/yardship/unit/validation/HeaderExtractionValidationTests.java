package org.yardship.unit.validation;

import org.junit.jupiter.api.Test;
import org.yardship.confcheck.outcome.HeaderResult;
import org.yardship.confcheck.outcome.ValidationOutcome;
import org.yardship.confcheck.port.ResponseSource;
import org.yardship.confcheck.validation.HeaderExtractionValidation;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link HeaderExtractionValidation}, the use case behind the {@code header}
 * subcommand and the {@code config} gate's header surface. These pin the extraction contract at
 * the cheapest possible seam: a pure {@link ResponseSource.Response} value in,
 * {@link ValidationOutcome} out — no HTTP, no CLI wiring.
 *
 * <p>Extraction semantics MUST match the shipped {@code http-header} current source
 * ({@code HttpHeaderCurrentSource}, slice 03) exactly: case-insensitive header-name matching, the
 * FIRST value of a repeated header, trimming, and — when a {@code regex} is configured — capture
 * group 1 of the FIRST match that parses (never the largest; see
 * {@code docs/adr/0030-http-header-current-source.md}, "The first match, not the largest"). A
 * validator that disagrees with the source it validates is worse than no validator, so several
 * tests here are lifted directly from {@code HttpHeaderCurrentSourceTests}' fixtures.
 *
 * <p>Every outcome — success AND failure — must carry the response's status code
 * ({@link HeaderResult#statusCode()}), so an operator reading a report can see they are reading a
 * version off (e.g.) a 403 and that this is working as designed.
 */
class HeaderExtractionValidationTests {

    private final HeaderExtractionValidation validation = new HeaderExtractionValidation();

    private static final VersionParser SEMVER = new VersionParser(VersionScheme.SEMVER);
    private static final String HEADER_NAME = "X-Jenkins";

    // --- No scheme, no regex: extraction-only ---------------------------------------------------

    @Test
    void headerFound_noSchemeNoRegex_isOkExtractionOnly_rawTextTrimmed() {
        ResponseSource.Response response = response(200, Map.of(HEADER_NAME, List.of("  2.568.2  ")));

        ValidationOutcome outcome = validation.validate(response, HEADER_NAME, Optional.empty(), false, Optional.empty());

        ValidationOutcome.HeaderOk ok = assertInstanceOf(ValidationOutcome.HeaderOk.class, outcome);
        assertEquals(ValidationOutcome.HeaderOk.EXIT_CODE, ok.exitCode());
        HeaderResult result = ok.result();
        assertEquals(200, result.statusCode());
        assertEquals("2.568.2", result.rawText().orElseThrow());
        assertFalse(result.schemeRequested());
        assertTrue(result.parsed().isEmpty());
    }

    // --- With scheme, no regex: raw trimmed value is parsed --------------------------------------

    @Test
    void headerFound_withScheme_valueParses_isOkWithParsedVersion() {
        ResponseSource.Response response = response(200, Map.of(HEADER_NAME, List.of("2.568.2")));

        ValidationOutcome outcome =
                validation.validate(response, HEADER_NAME, Optional.empty(), false, Optional.of(SEMVER));

        ValidationOutcome.HeaderOk ok = assertInstanceOf(ValidationOutcome.HeaderOk.class, outcome);
        HeaderResult result = ok.result();
        assertTrue(result.schemeRequested());
        assertEquals("2.568.2", result.parsed().orElseThrow().value());
        assertEquals(200, result.statusCode());
    }

    @Test
    void headerFound_withScheme_valueDoesNotParse_isValidButEmpty_carryingTheValueAndStatus() {
        ResponseSource.Response response = response(200, Map.of(HEADER_NAME, List.of("not-a-version")));

        ValidationOutcome outcome =
                validation.validate(response, HEADER_NAME, Optional.empty(), false, Optional.of(SEMVER));

        ValidationOutcome.HeaderValidButEmpty empty =
                assertInstanceOf(ValidationOutcome.HeaderValidButEmpty.class, outcome);
        assertEquals(ValidationOutcome.HeaderValidButEmpty.EXIT_CODE, empty.exitCode());
        assertEquals(200, empty.result().statusCode());
        assertEquals("not-a-version", empty.result().rawText().orElseThrow());
        assertTrue(empty.result().rejectionReason().isPresent());
    }

    // --- The load-bearing status-visibility case: a 403 carrying the header still resolves -----

    @Test
    void headerFound_on403Response_stillResolvesAndParses_reportingStatus403() {
        // The ADR-0030 motivating case: a secured Jenkins refuses the page (403) but volunteers
        // its version in X-Jenkins anyway.
        ResponseSource.Response response = response(403, Map.of(HEADER_NAME, List.of("2.568.2")));

        ValidationOutcome outcome =
                validation.validate(response, HEADER_NAME, Optional.empty(), false, Optional.of(SEMVER));

        ValidationOutcome.HeaderOk ok = assertInstanceOf(ValidationOutcome.HeaderOk.class, outcome);
        assertEquals(403, ok.result().statusCode(),
                "the status code must be visible on a PASSING outcome too, so an operator can see "
                        + "they are reading a version off a 403 and that this is by design");
        assertEquals("2.568.2", ok.result().parsed().orElseThrow().value());
    }

    // --- Header name matching: case-insensitive, repeated takes first --------------------------

    @Test
    void headerName_matchesCaseInsensitively() {
        // The wire carried lowercase 'x-jenkins'; configured/looked-up as 'X-Jenkins' — RFC 9110 §5.1.
        ResponseSource.Response response = response(200, Map.of("x-jenkins", List.of("2.568.2")));

        ValidationOutcome outcome =
                validation.validate(response, HEADER_NAME, Optional.empty(), false, Optional.of(SEMVER));

        ValidationOutcome.HeaderOk ok = assertInstanceOf(ValidationOutcome.HeaderOk.class, outcome);
        assertEquals("2.568.2", ok.result().parsed().orElseThrow().value());
    }

    @Test
    void repeatedHeader_takesTheFirstValue() {
        ResponseSource.Response response =
                response(200, Map.of(HEADER_NAME, List.of("2.568.2", "9.9.9")));

        ValidationOutcome outcome =
                validation.validate(response, HEADER_NAME, Optional.empty(), false, Optional.of(SEMVER));

        ValidationOutcome.HeaderOk ok = assertInstanceOf(ValidationOutcome.HeaderOk.class, outcome);
        assertEquals("2.568.2", ok.result().parsed().orElseThrow().value());
    }

    // --- Header absent vs. present-but-empty: distinct outcomes, both naming the status --------

    @Test
    void headerAbsent_isValidButEmpty_namingTheObservedStatus_noRawTextAttached() {
        ResponseSource.Response response = response(403, Map.of());

        ValidationOutcome outcome =
                validation.validate(response, HEADER_NAME, Optional.empty(), false, Optional.of(SEMVER));

        ValidationOutcome.HeaderValidButEmpty empty =
                assertInstanceOf(ValidationOutcome.HeaderValidButEmpty.class, outcome);
        assertEquals(403, empty.result().statusCode());
        assertTrue(empty.result().rawText().isEmpty(),
                "an absent header never obtained raw text, so there is nothing to attach");
        assertTrue(empty.message().contains("403"), "the message must name the observed status");
    }

    @Test
    void headerPresentButEmptyAfterTrimming_isValidButEmpty_namingTheObservedStatus_distinctFromAbsent() {
        ResponseSource.Response response = response(200, Map.of(HEADER_NAME, List.of("   ")));

        ValidationOutcome outcome =
                validation.validate(response, HEADER_NAME, Optional.empty(), false, Optional.of(SEMVER));

        ValidationOutcome.HeaderValidButEmpty empty =
                assertInstanceOf(ValidationOutcome.HeaderValidButEmpty.class, outcome);
        assertEquals(200, empty.result().statusCode());
        assertTrue(empty.message().contains("200"), "the message must name the observed status");
    }

    @Test
    void theThreeFailureOutcomes_haveDistinctMessages() {
        String absent = message(response(403, Map.of()));
        String presentButEmpty = message(response(200, Map.of(HEADER_NAME, List.of("  "))));
        String unparseable = message(response(200, Map.of(HEADER_NAME, List.of("garbage"))));

        assertNotEquals(absent, presentButEmpty,
                "absent and present-but-empty are different facts and must read differently");
        assertNotEquals(absent, unparseable);
        assertNotEquals(presentButEmpty, unparseable);
    }

    private String message(ResponseSource.Response response) {
        ValidationOutcome outcome =
                validation.validate(response, HEADER_NAME, Optional.empty(), false, Optional.of(SEMVER));
        return ((ValidationOutcome.HeaderValidButEmpty) outcome).message();
    }

    // --- regex: capture group 1 of the FIRST match, never the largest --------------------------

    @Test
    void withRegex_takesCaptureGroup1OfTheFirstMatch_evenWhenALaterMatchIsLarger() {
        ResponseSource.Response response =
                response(200, Map.of(HEADER_NAME, List.of("1.0.0 then later 9.9.9")));

        ValidationOutcome outcome = validation.validate(
                response, HEADER_NAME, Optional.of("(\\d+\\.\\d+\\.\\d+)"), false, Optional.of(SEMVER));

        ValidationOutcome.HeaderOk ok = assertInstanceOf(ValidationOutcome.HeaderOk.class, outcome);
        assertEquals("1.0.0", ok.result().parsed().orElseThrow().value(),
                "must pick the FIRST match (1.0.0), never the largest (9.9.9)");
    }

    /**
     * The exact fixture from ADR-0030's "The first match, not the largest" section: a current
     * version is a single observation, not a selection, so http-header must never report 22.04.
     */
    @Test
    void withRegex_nginxUbuntuServerHeader_yields_1_25_3_NOT_22_04() {
        ResponseSource.Response response =
                response(200, Map.of("Server", List.of("nginx/1.25.3 (Ubuntu/22.04)")));

        ValidationOutcome outcome = validation.validate(
                response, "Server", Optional.of("nginx/(\\d+\\.\\d+\\.\\d+)"), false, Optional.of(SEMVER));

        ValidationOutcome.HeaderOk ok = assertInstanceOf(ValidationOutcome.HeaderOk.class, outcome);
        assertEquals("1.25.3", ok.result().parsed().orElseThrow().value());
        assertNotEquals("22.04", ok.result().parsed().orElseThrow().value());
    }

    @Test
    void withRegex_matchesNothing_isValidButEmpty_carryingTheActualValueFound() {
        ResponseSource.Response response = response(200, Map.of(HEADER_NAME, List.of("no digits in here")));

        ValidationOutcome outcome = validation.validate(
                response, HEADER_NAME, Optional.of("(\\d+\\.\\d+\\.\\d+)"), false, Optional.of(SEMVER));

        ValidationOutcome.HeaderValidButEmpty empty =
                assertInstanceOf(ValidationOutcome.HeaderValidButEmpty.class, outcome);
        assertEquals("no digits in here", empty.result().rawText().orElseThrow());
    }

    /**
     * Pins the WITH-parser branch of {@code validateWithRegex}: an earlier match whose group 1
     * does not parse under the configured scheme must be SKIPPED, not treated as a failure —
     * mirroring production's {@code RegexVersionExtractor.firstIn}, which loops past unparseable
     * candidates. {@code 9999999999999999999.0.0} (19 nines) overflows long-based semver parsing
     * and must be skipped so the second, parseable match ({@code 1.25.3}) wins.
     */
    @Test
    void withRegex_andScheme_earlierMatchThatFailsToParse_isSkipped_laterParseableMatchWins() {
        ResponseSource.Response response = response(
                200, Map.of(HEADER_NAME, List.of("nginx/9999999999999999999.0.0 (real 1.25.3)")));

        ValidationOutcome outcome = validation.validate(
                response, HEADER_NAME, Optional.of("(\\d+\\.\\d+\\.\\d+)"), false, Optional.of(SEMVER));

        ValidationOutcome.HeaderOk ok = assertInstanceOf(ValidationOutcome.HeaderOk.class, outcome);
        assertEquals("1.25.3", ok.result().parsed().orElseThrow().value(),
                "the first match (9999999999999999999.0.0) overflows semver parsing and must be "
                        + "skipped, letting the second, parseable match (1.25.3) win");
    }

    /**
     * Pins the NO-parser branch of {@code validateWithRegex} — deliberately the opposite rule from
     * the with-parser branch above. Without a {@link VersionParser}, there is no criterion by
     * which a candidate could be judged "unparseable": extraction-only mode has nothing to skip
     * against, so it must take the FIRST regex match's captured text verbatim, full stop, even
     * text that a scheme would reject. This is NOT a bug to fix into consistency with the
     * with-parser branch — the two branches answer different questions ("first plausible text" vs.
     * "first text that parses"), and this test exists so that divergence stays intentional and
     * visible rather than accidental.
     */
    @Test
    void withRegex_andNoScheme_firstMatchWinsFullStop_evenTextThatWouldFailToParseUnderAScheme() {
        ResponseSource.Response response = response(
                200, Map.of(HEADER_NAME, List.of("nginx/9999999999999999999.0.0 (real 1.25.3)")));

        ValidationOutcome outcome = validation.validate(
                response, HEADER_NAME, Optional.of("(\\d+\\.\\d+\\.\\d+)"), false, Optional.empty());

        ValidationOutcome.HeaderOk ok = assertInstanceOf(ValidationOutcome.HeaderOk.class, outcome);
        assertFalse(ok.result().schemeRequested());
        assertEquals("9999999999999999999.0.0", ok.result().rawText().orElseThrow(),
                "extraction-only mode has no parser to judge 'unparseable' with, so it must take the "
                        + "FIRST match's text as-is — unlike the with-parser branch above, which skips "
                        + "this exact candidate and reports 1.25.3 instead");
    }

    @Test
    void withRegex_andNoScheme_isOkWithExtractedTextOfTheFirstMatch() {
        ResponseSource.Response response =
                response(200, Map.of(HEADER_NAME, List.of("1.0.0 then later 9.9.9")));

        ValidationOutcome outcome = validation.validate(
                response, HEADER_NAME, Optional.of("(\\d+\\.\\d+\\.\\d+)"), false, Optional.empty());

        ValidationOutcome.HeaderOk ok = assertInstanceOf(ValidationOutcome.HeaderOk.class, outcome);
        assertFalse(ok.result().schemeRequested());
        assertEquals("1.0.0", ok.result().rawText().orElseThrow());
    }

    @Test
    void invalidRegexSyntax_isConfigInvalid() {
        ResponseSource.Response response = response(200, Map.of(HEADER_NAME, List.of("2.568.2")));

        ValidationOutcome outcome =
                validation.validate(response, HEADER_NAME, Optional.of("(unclosed"), false, Optional.of(SEMVER));

        ValidationOutcome.ConfigInvalid invalid =
                assertInstanceOf(ValidationOutcome.ConfigInvalid.class, outcome);
        assertEquals(ValidationOutcome.ConfigInvalid.EXIT_CODE, invalid.exitCode());
    }

    @Test
    void regexWithNoCaptureGroup_isConfigInvalid() {
        ResponseSource.Response response = response(200, Map.of(HEADER_NAME, List.of("2.568.2")));

        ValidationOutcome outcome = validation.validate(
                response, HEADER_NAME, Optional.of("\\d+\\.\\d+\\.\\d+"), false, Optional.of(SEMVER));

        assertInstanceOf(ValidationOutcome.ConfigInvalid.class, outcome,
                "a regex with no capture group 1 has nothing to parse and must be rejected as config");
    }

    // --- strip-prerelease --------------------------------------------------------------------

    @Test
    void stripPreRelease_true_clearsThePrereleaseSegment_ofTheParsedValue() {
        ResponseSource.Response response = response(200, Map.of(HEADER_NAME, List.of("2.568.2-rc1")));

        ValidationOutcome outcome =
                validation.validate(response, HEADER_NAME, Optional.empty(), true, Optional.of(SEMVER));

        ValidationOutcome.HeaderOk ok = assertInstanceOf(ValidationOutcome.HeaderOk.class, outcome);
        assertEquals("2.568.2", ok.result().parsed().orElseThrow().value());
    }

    @Test
    void stripPreRelease_false_preservesThePrereleaseSegment() {
        ResponseSource.Response response = response(200, Map.of(HEADER_NAME, List.of("2.568.2-rc1")));

        ValidationOutcome outcome =
                validation.validate(response, HEADER_NAME, Optional.empty(), false, Optional.of(SEMVER));

        ValidationOutcome.HeaderOk ok = assertInstanceOf(ValidationOutcome.HeaderOk.class, outcome);
        assertTrue(ok.result().parsed().orElseThrow().value().contains("rc1"),
                "with strip-prerelease false, the prerelease segment must be preserved; was: "
                        + ok.result().parsed().orElseThrow().value());
    }

    @Test
    void stripPreRelease_true_rawTextReportsTheOriginalUnstrippedValue() {
        // Mirrors PointerResult#rawText()'s contract: rawText always reports what was actually
        // extracted, unmodified; the strip's effect is visible only in parsed().
        ResponseSource.Response response = response(200, Map.of(HEADER_NAME, List.of("2.568.2-rc1")));

        ValidationOutcome outcome =
                validation.validate(response, HEADER_NAME, Optional.empty(), true, Optional.of(SEMVER));

        ValidationOutcome.HeaderOk ok = assertInstanceOf(ValidationOutcome.HeaderOk.class, outcome);
        assertEquals("2.568.2-rc1", ok.result().rawText().orElseThrow());
    }

    private static ResponseSource.Response response(int statusCode, Map<String, List<String>> headers) {
        return new ResponseSource.Response(statusCode, headers);
    }
}
