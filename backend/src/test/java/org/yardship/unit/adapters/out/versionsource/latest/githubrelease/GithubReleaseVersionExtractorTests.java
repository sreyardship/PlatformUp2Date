package org.yardship.unit.adapters.out.versionsource.latest.githubrelease;

import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.VersionFetchException;
import org.yardship.adapters.out.versionsource.http.HttpGetResponse;
import org.yardship.adapters.out.versionsource.latest.githubrelease.GithubReleaseVersionExtractor;
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
 * Unit tests for {@link GithubReleaseVersionExtractor}, the {@code github-release} rule applied to
 * the final response the shared transport returns: a 2xx gate, the {@code GET /releases} JSON array
 * decoded, and "latest" selected as the LARGEST version by {@code tag_name} among releases that are
 * neither a prerelease nor a draft — not GitHub's time-ordered {@code /releases/latest} and not the
 * release {@code name} (ADR-0010). Fed fake {@link HttpGetResponse}s — NO Arc, NO HTTP.
 */
class GithubReleaseVersionExtractorTests {

    private static final URI RELEASES_URI =
            URI.create("http://localhost:8089/repos/owner/repo/releases?per_page=30");
    private static final VersionParser SEMVER_PARSER = new VersionParser(VersionScheme.SEMVER);

    private final GithubReleaseVersionExtractor extractor = new GithubReleaseVersionExtractor(SEMVER_PARSER);

    @Test
    void extract_picksTheLargestSemver_acrossTagNames_notReleaseName() {
        VersionValue result = extractor.extract(ok(
                release("v1.2.0", "release-named-anything", false, false),
                release("v1.10.0", "release-named-anything-2", false, false),
                release("v1.3.0", "release-named-anything-3", false, false)));

        assertEquals("1.10.0", result.value(), "must select by tag_name's semver value, not by name");
    }

    @Test
    void extract_ignoresPrereleaseReleases() {
        VersionValue result = extractor.extract(ok(
                release("v2.0.0", "n", false, false),
                release("v3.0.0-rc.1", "n", true, false)));

        assertEquals("2.0.0", result.value(), "a prerelease=true release must never win, even if numerically larger");
    }

    @Test
    void extract_ignoresDraftReleases() {
        VersionValue result = extractor.extract(ok(
                release("v2.0.0", "n", false, false),
                release("v9.9.9", "n", false, true)));

        assertEquals("2.0.0", result.value(), "a draft=true release must never win, even if numerically larger");
    }

    @Test
    void extract_aNewerPublishedButLowerVersionRelease_doesNotWin() {
        // The multi-branch created_at trap: GitHub's time-ordering (what /releases/latest used) would
        // pick whichever release was published most recently. Selection is purely by semver value;
        // list position (GitHub's publish order) must not matter.
        VersionValue result = extractor.extract(ok(
                release("v2.5.0", "older-list-position", false, false),
                release("v1.9.9", "newer-list-position-but-lower-version", false, false)));

        assertEquals("2.5.0", result.value(),
                "the largest semver must win regardless of publish recency / list position");
    }

    @Test
    void extract_skipsAnUnparseableTagName_withoutFailingTheRead() {
        VersionValue result = extractor.extract(ok(
                release("not-a-semver", "n", false, false),
                release("v1.4.0", "n", false, false)));

        assertEquals("1.4.0", result.value(), "an unparseable tag_name must be skipped, not crash the read");
    }

    @Test
    void extract_treatsDuplicateOrTiedVersions_asHarmless() {
        VersionValue result = extractor.extract(ok(
                release("v1.0.0", "n", false, false),
                release("v1.0.0", "n", false, false),
                release("v0.9.0", "n", false, false)));

        assertEquals("1.0.0", result.value());
    }

    @Test
    void extract_ignoresUnmodelledFields_inGithubsReleaseResponse() {
        HttpGetResponse response = json(200, """
                [{
                  "url":"https://api.github.com/repos/mastodon/mastodon/releases/1",
                  "html_url":"https://github.com/mastodon/mastodon/releases/tag/v4.6.0",
                  "tag_name":"v4.6.0",
                  "name":"v4.6.0",
                  "prerelease":false,
                  "draft":false
                }]
                """);

        assertEquals("4.6.0", extractor.extract(response).value());
    }

    @Test
    void extract_throws_whenNoReleaseSurvivesFiltering_namingTheUri() {
        HttpGetResponse response = ok(
                release("v9.0.0", "n", true, false),
                release("v8.0.0", "n", false, true));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> extractor.extract(response),
                "no release surviving the prerelease/draft filter is a per-app scrape failure");

        assertTrue(ex.getMessage().contains(RELEASES_URI.toString()),
                "the failure must name where the releases were read from; was: " + ex.getMessage());
    }

    @Test
    void extract_throws_whenTheReleaseListIsEmpty() {
        HttpGetResponse response = ok();

        assertThrows(IllegalStateException.class, () -> extractor.extract(response));
    }

    @Test
    void extract_throws_whenEveryTagNameIsUnparseable() {
        HttpGetResponse response = ok(
                release("not-a-semver", "n", false, false),
                release("also-not-a-semver", "n", false, false));

        assertThrows(IllegalStateException.class, () -> extractor.extract(response),
                "if every survivor's tag_name is unparseable, none survive selection — must throw");
    }

    // --- the 2xx gate lives here, not in the transport ----------------------------------------

    @Test
    void extract_throwsAVersionFetchException_onANon2xxResponse_namingTheStatusAndTheBody() {
        HttpGetResponse rateLimited = json(403, "{\"message\":\"API rate limit exceeded\"}");

        VersionFetchException ex = assertThrows(VersionFetchException.class, () -> extractor.extract(rateLimited));

        assertEquals(403, ex.status());
        assertEquals("{\"message\":\"API rate limit exceeded\"}", ex.body());
        assertTrue(ex.getMessage().contains("403"), "must name the status; was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("API rate limit exceeded"), "must carry the body; was: " + ex.getMessage());
    }

    @Test
    void extract_onANon2xxResponse_truncatesALongBodyInTheMessage_butRetainsItOnTheException() {
        String longBody = "x".repeat(2000);
        HttpGetResponse error = json(503, longBody);

        VersionFetchException ex = assertThrows(VersionFetchException.class, () -> extractor.extract(error));

        assertTrue(ex.getMessage().endsWith("…[truncated]"), "was: " + ex.getMessage());
        assertFalse(ex.getMessage().contains(longBody));
        assertEquals(longBody, ex.body());
    }

    @Test
    void extract_throwsAVersionFetchException_whenA2xxBodyIsNotAReleaseList() {
        // A stale /releases/latest URL returns a single object, not an array (ADR-0010).
        HttpGetResponse single = json(200, "{\"tag_name\":\"v1.0.0\",\"prerelease\":false,\"draft\":false}");

        VersionFetchException ex = assertThrows(VersionFetchException.class, () -> extractor.extract(single));

        assertTrue(ex.getMessage().toLowerCase().contains("github releases"),
                "the failure must say what could not be parsed; was: " + ex.getMessage());
    }

    // --- fixtures -----------------------------------------------------------------------------------

    // --- semver-optional-patch (ADR-0041): Jellyfin's two-digit release tags ----------------

    @Test
    void extract_selectsTheTwoPartTag_underAnOptionalPatchParser() {
        GithubReleaseVersionExtractor optionalPatch =
                new GithubReleaseVersionExtractor(VersionParser.semver(true));

        VersionValue result = optionalPatch.extract(jellyfinShapedReleases());

        assertEquals("12.2", result.value(),
                "v12.2 wins: the rc is a flagged prerelease, '12', 'unstable' and the date tag are not "
                        + "two- or three-part semver");
    }

    @Test
    void extract_throws_forTheSameTwoPartTags_underAStrictParser() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> extractor.extract(jellyfinShapedReleases()));

        assertTrue(ex.getMessage().contains("No release with a parseable"),
                "strict semver keeps today's 'no parseable tag' failure; was: " + ex.getMessage());
    }

    private static HttpGetResponse jellyfinShapedReleases() {
        return ok(
                release("v12.2", "n", false, false),
                release("v12.0-rc7", "n", true, false),
                release("12", "n", false, false),
                release("unstable", "n", false, false),
                release("2026101212", "n", false, false));
    }

    private static HttpGetResponse ok(String... releases) {
        return json(200, "[" + String.join(",", releases) + "]");
    }

    private static HttpGetResponse json(int status, String body) {
        return new HttpGetResponse(RELEASES_URI, status, Map.of("Content-Type", List.of("application/json")), body);
    }

    private static String release(String tagName, String name, boolean prerelease, boolean draft) {
        return "{\"tag_name\":\"" + tagName + "\",\"name\":\"" + name + "\",\"prerelease\":" + prerelease
                + ",\"draft\":" + draft + "}";
    }
}
