package org.yardship.unit.adapters.out.versionsource.regex;

import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.regex.RegexVersionExtractor;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import org.yardship.core.domain.primitives.VersionValue;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RegexVersionExtractor} — the leg-neutral, shared extraction core factored
 * out of {@code HttpRegexLatestSource} (see {@code docs/adr/0030-http-header-current-source.md},
 * section "The first match, not the largest"). It owns pattern compilation, the
 * compiles-and-has-a-capture-group validation, group-1 extraction, and tolerance of unparseable
 * candidates. It exposes TWO selection rules over that shared machinery:
 *
 * <ul>
 *   <li>{@link RegexVersionExtractor#largestIn}: the largest parseable candidate — what
 *       {@code http-regex} uses today (same largest-wins rule as {@code github-release} /
 *       ADR-0010 and {@code oci-registry} / ADR-0014).</li>
 *   <li>{@link RegexVersionExtractor#firstIn}: capture group 1 of the FIRST match in input order,
 *       even when a later match parses larger. No production caller yet — this is for slice 03's
 *       current-leg source, where a current version is a single observation, not a selection.</li>
 * </ul>
 *
 * <p>Both selection methods return {@link Optional#empty()} — never throw — when nothing matches or
 * nothing parses; the caller (an adapter) words the kind-appropriate failure. Pattern-validation
 * failures (non-compiling regex, zero capture groups) THROW at construction, so a bad pattern
 * still fails boot when a factory builds its source.
 */
class RegexVersionExtractorTests {

    private static final VersionParser SEMVER_PARSER = new VersionParser(VersionScheme.SEMVER);
    private static final String TEST_LABEL = "'http-regex' latest source";

    // --- largestIn: the http-regex selection rule --------------------------------------------------

    @Test
    void largestIn_picksTheMaximumCandidate_underTheAppsScheme() {
        RegexVersionExtractor extractor = new RegexVersionExtractor(TEST_LABEL, "(\\d+\\.\\d+\\.\\d+)", SEMVER_PARSER);

        Optional<VersionValue> result = extractor.largestIn("1.2.0\n2.0.0\n1.9.9");

        assertTrue(result.isPresent());
        assertEquals("2.0.0", result.get().value());
    }

    @Test
    void largestIn_returnsEmpty_whenNothingMatches() {
        RegexVersionExtractor extractor = new RegexVersionExtractor(TEST_LABEL, "Version: (\\S+)", SEMVER_PARSER);

        Optional<VersionValue> result = extractor.largestIn("no version tokens here");

        assertTrue(result.isEmpty(), "no match must yield an empty result, not a thrown exception");
    }

    @Test
    void largestIn_returnsEmpty_whenMatchesExistButNoneParse() {
        RegexVersionExtractor extractor = new RegexVersionExtractor(TEST_LABEL, "Version: (\\S+)", SEMVER_PARSER);

        Optional<VersionValue> result = extractor.largestIn(
                "Version: not-semver\nVersion: also-not-semver");

        assertTrue(result.isEmpty(),
                "matches that all fail to parse must yield an empty result, not a thrown exception");
    }

    @Test
    void largestIn_skipsUnparseableCandidates_andPicksTheLargestSurvivor() {
        RegexVersionExtractor extractor = new RegexVersionExtractor(TEST_LABEL, "token: (\\S+)", SEMVER_PARSER);

        Optional<VersionValue> result = extractor.largestIn("token: not-a-semver\ntoken: 2.0.0");

        assertTrue(result.isPresent());
        assertEquals("2.0.0", result.get().value(),
                "the unparseable candidate must be skipped, leaving the valid one as the result");
    }

    @Test
    void largestIn_usesCaptureGroup1_notTheFullMatch() {
        // The full match includes the "release v" prefix; only group 1 is the version token.
        RegexVersionExtractor extractor = new RegexVersionExtractor(TEST_LABEL,
                "release v(\\d+\\.\\d+\\.\\d+)", SEMVER_PARSER);

        Optional<VersionValue> result = extractor.largestIn("release v1.0.0\nrelease v1.3.0");

        assertTrue(result.isPresent());
        assertEquals("1.3.0", result.get().value(),
                "capture group 1 must be the parsed token, not the full regex match");
    }

    // --- firstIn: slice 03's current-leg selection rule -------------------------------------------

    @Test
    void firstIn_picksTheFirstMatchInInputOrder_evenWhenALaterMatchParsesLarger() {
        // This is the rule's whole point: firstIn is NOT largest-wins. The first match (1.2.0) is
        // smaller than a later match (3.0.0); firstIn must still return the first one.
        RegexVersionExtractor extractor = new RegexVersionExtractor(TEST_LABEL, "(\\d+\\.\\d+\\.\\d+)", SEMVER_PARSER);

        Optional<VersionValue> result = extractor.firstIn("1.2.0\n3.0.0\n1.9.9");

        assertTrue(result.isPresent());
        assertEquals("1.2.0", result.get().value(),
                "firstIn must return the first match in input order, not the largest");
    }

    @Test
    void firstIn_skipsUnparseableCandidates_returningTheFirstParseableOne() {
        RegexVersionExtractor extractor = new RegexVersionExtractor(TEST_LABEL, "token: (\\S+)", SEMVER_PARSER);

        Optional<VersionValue> result = extractor.firstIn("token: not-a-semver\ntoken: 2.0.0");

        assertTrue(result.isPresent());
        assertEquals("2.0.0", result.get().value(),
                "an unparseable first candidate must be skipped in favour of the first parseable one");
    }

    @Test
    void firstIn_returnsEmpty_whenNothingMatches() {
        RegexVersionExtractor extractor = new RegexVersionExtractor(TEST_LABEL, "Version: (\\S+)", SEMVER_PARSER);

        Optional<VersionValue> result = extractor.firstIn("no version tokens here");

        assertTrue(result.isEmpty(), "no match must yield an empty result, not a thrown exception");
    }

    @Test
    void firstIn_returnsEmpty_whenMatchesExistButNoneParse() {
        RegexVersionExtractor extractor = new RegexVersionExtractor(TEST_LABEL, "Version: (\\S+)", SEMVER_PARSER);

        Optional<VersionValue> result = extractor.firstIn(
                "Version: not-semver\nVersion: also-not-semver");

        assertTrue(result.isEmpty(),
                "matches that all fail to parse must yield an empty result, not a thrown exception");
    }

    @Test
    void firstIn_usesCaptureGroup1_notTheFullMatch() {
        RegexVersionExtractor extractor = new RegexVersionExtractor(TEST_LABEL,
                "release v(\\d+\\.\\d+\\.\\d+)", SEMVER_PARSER);

        Optional<VersionValue> result = extractor.firstIn("release v1.0.0\nrelease v1.3.0");

        assertTrue(result.isPresent());
        assertEquals("1.0.0", result.get().value(),
                "capture group 1 must be the parsed token, not the full regex match, "
                        + "and firstIn must return the FIRST such token");
    }

    // --- the motivating real-world case: both rules over the SAME input ---------------------------

    /**
     * Against an nginx {@code Server} header, a loose {@code \d+\.\d+} pattern captures BOTH the
     * app version (from {@code 1.25.3}, group 1 yields {@code "1.25"} — first in the string) and the
     * OS version embedded in the parenthetical ({@code 22.04}, later in the string). Parsed under a
     * calver {@code YY.0M} scheme both candidates are valid versions, and {@code 22.04} numerically
     * outranks {@code 1.25} (year 22 > year 1) — exactly the trap the issue describes: a largest-wins
     * rule applied to a single observation silently reports the OS version as the app's own.
     * {@code firstIn} must report the app's own version (first in input order); {@code largestIn}
     * demonstrates the misreport. A calver scheme is used here (rather than semver) purely so BOTH
     * captured tokens are valid, parseable versions — semver4j rejects a bare two-component
     * {@code "22.04"} outright, which would mask the point being illustrated. Asserting both rules
     * over the same input in one test is what makes the distinction between "selection" (latest) and
     * "observation" (current) comprehensible.
     */
    @Test
    void nginxServerHeader_firstInYieldsAppVersion_largestInWouldYieldOsVersion() {
        String header = "Server: nginx/1.25.3 (Ubuntu/22.04)";
        VersionParser calverParser = new VersionParser(VersionScheme.CALVER, "YY.0M");
        RegexVersionExtractor extractor = new RegexVersionExtractor(TEST_LABEL, "(\\d+\\.\\d+)", calverParser);

        Optional<VersionValue> first = extractor.firstIn(header);
        Optional<VersionValue> largest = extractor.largestIn(header);

        assertTrue(first.isPresent());
        assertEquals("1.25", first.get().value(),
                "firstIn must yield the app's own version (first token), not the OS version");

        assertTrue(largest.isPresent());
        assertEquals("22.04", largest.get().value(),
                "largestIn demonstrates the failure mode firstIn exists to avoid: it would report "
                        + "the OS version as if it were the app's current version");
    }

    // --- construction: pattern validation (throws, matching HttpRegexLatestSourceFactory today) ---

    @Test
    void construction_rejectsANonCompilingPattern() {
        assertThrows(IllegalArgumentException.class,
                () -> new RegexVersionExtractor(TEST_LABEL, "Version: (\\S+", SEMVER_PARSER),
                "a pattern that fails to compile must throw at construction (boot failure)");
    }

    @Test
    void construction_rejectsAPatternWithZeroCaptureGroups() {
        assertThrows(IllegalArgumentException.class,
                () -> new RegexVersionExtractor(TEST_LABEL, "Version: \\S+", SEMVER_PARSER),
                "a pattern with no capture group must throw at construction — group 1 is required");
    }
}
