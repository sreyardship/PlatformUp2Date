package org.yardship.core.domain.primitives;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.PatternSyntaxException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link VersionPattern} — the single shared implementation of "compile a regex,
 * require capture group 1, hand back every match's group 1 in input order" (see
 * {@code docs/adr/0032-config-errors-degrade-per-app-never-the-boot.md} and
 * {@code docs/adr/0030-http-header-current-source.md}). Deliberately has no {@link VersionParser}
 * dependency: this class only matches, it never parses or selects, so these tests never construct
 * or reference a {@link VersionParser}.
 *
 * <p>Pure domain unit test — no Quarkus context needed, mirroring {@code CalverFormatTests} and
 * {@code ChangelogTemplateTests}.
 */
class VersionPatternTests {

    // --- construction: compile-and-capture-group validation --------------------------------------

    @Test
    void constructor_acceptsAPatternThatCompilesAndHasACaptureGroup() {
        VersionPattern pattern = new VersionPattern("(\\d+\\.\\d+\\.\\d+)");

        assertEquals(List.of("1.2.3"), pattern.rawCandidates("version 1.2.3"));
    }

    @Test
    void constructor_nonCompilingPattern_retainsThePatternSyntaxExceptionAsTheCause() {
        // Part of the contract, not an accident: RegexVersionExtractor tells a compile failure
        // apart from a missing capture group by inspecting the cause, so that it can re-word each
        // with its own kind label. Dropping the cause here would silently relabel every
        // non-compiling regex as "must have at least one capture group".
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new VersionPattern("Version: (\\S+"));

        assertInstanceOf(PatternSyntaxException.class, thrown.getCause(),
                "a compile failure must retain its PatternSyntaxException as the cause");
    }

    @Test
    void constructor_zeroCaptureGroupPattern_carriesNoCause() {
        // The other half of that discriminator: no cause means "compiled, but has no group 1".
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new VersionPattern("Version: \\S+"));

        assertNull(thrown.getCause(),
                "a capture-group failure is not a compile failure and must carry no cause");
    }

    @Test
    void constructor_acceptsAPatternWithMultipleCaptureGroups_onlyGroup1MattersLater() {
        // Two capture groups is legal at construction; only group 1 is ever read by rawCandidates.
        VersionPattern pattern = new VersionPattern("(\\d+)\\.(\\d+)");

        assertEquals(List.of("1"), pattern.rawCandidates("1.2"));
    }

    // --- messages are neutral: no source kind, leg, or config field name -------------------------

    @Test
    void zeroCaptureGroupMessage_matchesTheEstablishedNeutralWording() {
        // This is conf-check's existing RegexPatternValidation wording verbatim (ADR-0032) — pinned
        // exactly so conf-check can render VersionPattern's message directly with no rewriting.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new VersionPattern("Version: \\S+"));

        assertEquals("Regex 'Version: \\S+' has no capture group 1 to parse a version from.",
                ex.getMessage());
    }

    @Test
    void zeroCaptureGroupMessage_namesNoSourceKindLegOrConfigField() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new VersionPattern("Version: \\S+"));

        String message = ex.getMessage().toLowerCase();
        assertFalse(message.contains("http-regex"), "message must not name the http-regex kind: " + message);
        assertFalse(message.contains("http-header"), "message must not name the http-header kind: " + message);
        assertFalse(message.contains("http-json"), "message must not name the http-json kind: " + message);
        assertFalse(message.contains("latest source"), "message must not name a leg: " + message);
        assertFalse(message.contains("current source"), "message must not name a leg: " + message);
        assertFalse(message.contains("version-header"), "message must not name a config field: " + message);
    }

    @Test
    void nonCompilingPatternMessage_namesNoSourceKindLegOrConfigField() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new VersionPattern("Version: (\\S+"));

        String message = ex.getMessage().toLowerCase();
        assertFalse(message.contains("http-regex"), "message must not name the http-regex kind: " + message);
        assertFalse(message.contains("http-header"), "message must not name the http-header kind: " + message);
        assertFalse(message.contains("http-json"), "message must not name the http-json kind: " + message);
        assertFalse(message.contains("latest source"), "message must not name a leg: " + message);
        assertFalse(message.contains("current source"), "message must not name a leg: " + message);
        assertFalse(message.contains("version-header"), "message must not name a config field: " + message);
    }

    // --- rawCandidates: every match's group 1, in input order, no parsing ------------------------

    @Test
    void rawCandidates_returnsGroup1OfEveryMatch_inInputOrder() {
        VersionPattern pattern = new VersionPattern("(\\d+\\.\\d+\\.\\d+)");

        List<String> candidates = pattern.rawCandidates("1.2.0\n2.0.0\n1.9.9");

        assertEquals(List.of("1.2.0", "2.0.0", "1.9.9"), candidates);
    }

    @Test
    void rawCandidates_returnsEmptyList_whenNothingMatches() {
        VersionPattern pattern = new VersionPattern("Version: (\\S+)");

        List<String> candidates = pattern.rawCandidates("no version tokens here");

        assertTrue(candidates.isEmpty(), "no match must yield an empty list, not null or a thrown exception");
    }

    @Test
    void rawCandidates_usesCaptureGroup1_notTheFullMatch() {
        VersionPattern pattern = new VersionPattern("release v(\\d+\\.\\d+\\.\\d+)");

        List<String> candidates = pattern.rawCandidates("release v1.0.0\nrelease v1.3.0");

        assertEquals(List.of("1.0.0", "1.3.0"), candidates,
                "capture group 1 must be returned, not the full regex match");
    }

    @Test
    void rawCandidates_returnsRawUnparsedStrings_doesNotValidateOrFilterCandidates() {
        // No VersionParser involved: an unparseable candidate is still returned raw. Parsing,
        // selection, and rejection-reporting are each caller's own job, not VersionPattern's.
        VersionPattern pattern = new VersionPattern("token: (\\S+)");

        List<String> candidates = pattern.rawCandidates("token: not-a-semver\ntoken: 2.0.0");

        assertEquals(List.of("not-a-semver", "2.0.0"), candidates);
    }

    @Test
    void rawCandidates_doesNotDeduplicate_repeatedIdenticalMatchesAllAppear() {
        VersionPattern pattern = new VersionPattern("(\\d+\\.\\d+\\.\\d+)");

        List<String> candidates = pattern.rawCandidates("1.0.0 and again 1.0.0");

        assertEquals(List.of("1.0.0", "1.0.0"), candidates);
    }
}
