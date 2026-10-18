package org.yardship.unit.adapters.out.versionsource.latest.httpregex;

import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.VersionFetchException;
import org.yardship.adapters.out.versionsource.http.HttpGetResponse;
import org.yardship.adapters.out.versionsource.latest.httpregex.HttpRegexVersionExtractor;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import org.yardship.core.domain.primitives.VersionValue;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link HttpRegexVersionExtractor}, the {@code http-regex} rule applied to the final
 * response the shared transport returns: a 2xx gate, the body as text whatever its content type,
 * capture group 1 of every match parsed under the app's scheme, the largest winning (ADR-0017).
 * Fed fake {@link HttpGetResponse}s — NO Arc, NO HTTP.
 */
class HttpRegexVersionExtractorTests {

    private static final URI FEED_URI = URI.create("http://localhost:8089/meta-release-lts");
    private static final VersionParser SEMVER_PARSER = new VersionParser(VersionScheme.SEMVER);
    private static final VersionParser CALVER_UBUNTU = new VersionParser(VersionScheme.CALVER, "YY.0M");

    @Test
    void extract_picksTheLargestVersion_underTheAppsScheme_fromPlainText() {
        VersionValue result = extractor("Version: (\\d+\\.\\d+)", CALVER_UBUNTU).extract(text("""
                Dist: focal
                Version: 20.04
                Dist: noble
                Version: 24.04
                Dist: jammy
                Version: 22.04
                """));

        assertEquals("24.04", result.value(), "the largest calver among every match wins, not the first or last");
    }

    @Test
    void extract_readsHtml_asText() {
        VersionValue result = extractor("href=\"(\\d+\\.\\d+\\.\\d+)/\"", SEMVER_PARSER).extract(
                new HttpGetResponse(FEED_URI, 200, Map.of("Content-Type", List.of("text/html; charset=utf-8")),
                        "<pre><a href=\"1.2.0/\">1.2.0/</a>\n<a href=\"2.0.0/\">2.0.0/</a></pre>"));

        assertEquals("2.0.0", result.value());
    }

    @Test
    void extract_usesCaptureGroup1_notTheFullMatch() {
        VersionValue result = extractor("release v(\\d+\\.\\d+\\.\\d+)", SEMVER_PARSER)
                .extract(text("release v1.0.0\nrelease v1.3.0"));

        assertEquals("1.3.0", result.value(), "the full match 'release v1.3.0' is unparseable; group 1 is the token");
    }

    @Test
    void extract_skipsUnparseableMatches_andPicksTheLargestSurvivor() {
        VersionValue result = extractor("token: (\\S+)", SEMVER_PARSER)
                .extract(text("token: not-a-semver\ntoken: 2.0.0\ntoken: 1.9.9"));

        assertEquals("2.0.0", result.value());
    }

    @Test
    void extract_throws_whenNothingMatches_namingTheRegexAndTheUri() {
        HttpRegexVersionExtractor extractor = extractor("Version: (\\S+)", SEMVER_PARSER);
        HttpGetResponse response = text("no version tokens here");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> extractor.extract(response));

        assertTrue(ex.getMessage().contains("Version: (\\S+)"), "must name the regex; was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains(FEED_URI.toString()), "must name the uri; was: " + ex.getMessage());
    }

    @Test
    void extract_throws_whenEveryMatchIsUnparseable() {
        HttpRegexVersionExtractor extractor = extractor("Version: (\\S+)", SEMVER_PARSER);
        HttpGetResponse response = text("Version: not-semver\nVersion: also-not-semver");

        assertThrows(IllegalStateException.class, () -> extractor.extract(response));
    }

    // --- the 2xx gate lives here, not in the transport ----------------------------------------

    @Test
    void extract_throwsAVersionFetchException_onANon2xx_namingTheStatusAndUri_butNeverTheBody() {
        HttpRegexVersionExtractor extractor = extractor("(\\d+\\.\\d+\\.\\d+)", SEMVER_PARSER);
        HttpGetResponse notFound = new HttpGetResponse(FEED_URI, 404, Map.of(), "<html>Not Found 9.9.9</html>");

        VersionFetchException ex = assertThrows(VersionFetchException.class, () -> extractor.extract(notFound));

        assertEquals(404, ex.status());
        assertTrue(ex.getMessage().contains("404"), "must name the status; was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains(FEED_URI.toString()), "must name the uri; was: " + ex.getMessage());
        assertFalse(ex.getMessage().contains("Not Found"),
                "a release page can be a whole HTML document; the message must not quote it (ADR-0033)");
        assertEquals("<html>Not Found 9.9.9</html>", ex.body(), "the exception itself still carries the body");
    }

    // --- structural validation happens at construction (ADR-0032) --------------------------------

    @Test
    void constructor_rejectsARegexThatDoesNotCompile_namingTheHttpRegexLatestSource() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> extractor("Version: (\\S+", SEMVER_PARSER));

        assertTrue(ex.getMessage().contains("'http-regex' latest source"), "was: " + ex.getMessage());
    }

    @Test
    void constructor_rejectsARegexWithNoCaptureGroup() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> extractor("Version: \\S+", SEMVER_PARSER));

        assertTrue(ex.getMessage().contains("capture group"), "was: " + ex.getMessage());
    }

    private static HttpRegexVersionExtractor extractor(String regex, VersionParser parser) {
        return new HttpRegexVersionExtractor(regex, parser);
    }

    private static HttpGetResponse text(String body) {
        return new HttpGetResponse(FEED_URI, 200, Map.of("Content-Type", List.of("text/plain; charset=utf-8")), body);
    }
}
