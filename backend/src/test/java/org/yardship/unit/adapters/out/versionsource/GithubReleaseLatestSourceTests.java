package org.yardship.unit.adapters.out.versionsource;

import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionclient.GithubReleaseClient;
import org.yardship.adapters.out.versionclient.GithubReleaseResponseDTO;
import org.yardship.adapters.out.versionsource.GithubReleaseLatestSource;
import org.yardship.core.domain.primitives.Version;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link GithubReleaseLatestSource}'s selection logic: "latest" is the LARGEST semver
 * among non-prerelease, non-draft releases — selected by {@code tag_name}, NOT GitHub's time-ordered
 * {@code GET /releases/latest} and NOT release {@code name} (see ADR-0010).
 *
 * <p><b>Test seam this issue must build:</b> {@code GithubReleaseLatestSource} currently builds its
 * {@link GithubReleaseClient} lazily and internally (no injection point). To unit-test the selection
 * logic without HTTP/Quarkus, the implementer must add a public (test-visible, like the factory's
 * token-only constructor) constructor that accepts a pre-built {@link GithubReleaseClient} directly,
 * bypassing the lazy {@code QuarkusRestClientBuilder} path entirely:
 *
 * <pre>{@code
 * // public; visible for testing — this test lives in a different package than the production class
 * public GithubReleaseLatestSource(GithubReleaseClient client) { ... }
 * }</pre>
 *
 * <p>The fake {@link GithubReleaseClient} below is a lambda implementing the real interface (the
 * port-level seam), not an ad-hoc mock — consistent with this codebase's "fakes over mocks" style.
 * It is a lambda rather than a named class deliberately: {@code GithubReleaseClient} is one of this
 * module's CDI-discoverable REST-client interfaces, and under {@code @QuarkusTest} elsewhere in the
 * suite Arc's classpath bean-scanning will try to satisfy a named fake CLASS's constructor parameters
 * via injection (it does not scan lambdas). The new client method is assumed to be
 * {@code List<GithubReleaseResponseDTO> releases(int perPage)} (per the {@code @QueryParam("per_page")}
 * acceptance criterion); the fake below ignores the {@code perPage} argument and simply returns the
 * fixed list configured by each test, since pagination/count behavior is integration-level (covered by
 * {@code GithubReleaseLatestSourceIT}), not selection-logic.
 *
 * <p>{@link GithubReleaseResponseDTO} is assumed to gain two new public fields ({@code prerelease},
 * {@code draft}, both {@code boolean}) alongside the existing {@code name} and a new {@code tag_name}
 * -backed {@code tagName} field (Jackson/JSON-B will map the snake_case {@code tag_name} JSON key onto
 * a {@code tagName} Java field at the DTO level — see the IT for the real-deserialization check). This
 * unit suite constructs the DTO directly via field assignment, so the exact JSON-to-field name mapping
 * is irrelevant here.
 */
class GithubReleaseLatestSourceTests {

    @Test
    void version_picksTheLargestSemver_acrossTagNames_notReleaseName() {
        GithubReleaseLatestSource source = sourceWith(
                release("v1.2.0", "release-named-anything", false, false),
                release("v1.10.0", "release-named-anything-2", false, false),
                release("v1.3.0", "release-named-anything-3", false, false));

        Version result = source.version();

        assertEquals("1.10.0", result.value(), "must select by tag_name's semver value, not by name");
    }

    @Test
    void version_ignoresPrereleaseReleases() {
        GithubReleaseLatestSource source = sourceWith(
                release("v2.0.0", "n", false, false),
                release("v3.0.0-rc.1", "n", true, false));

        Version result = source.version();

        assertEquals("2.0.0", result.value(), "a prerelease=true release must never win, even if numerically larger");
    }

    @Test
    void version_ignoresDraftReleases() {
        GithubReleaseLatestSource source = sourceWith(
                release("v2.0.0", "n", false, false),
                release("v9.9.9", "n", false, true));

        Version result = source.version();

        assertEquals("2.0.0", result.value(), "a draft=true release must never win, even if numerically larger");
    }

    @Test
    void version_aNewerPublishedButLowerVersionRelease_doesNotWin() {
        // The multi-branch created_at trap: GitHub's time-ordering (what /releases/latest used) would
        // pick whichever release was published most recently, regardless of its version number. A repo
        // maintaining an older stable branch (e.g. 1.x) alongside a newer 2.x line can publish a 1.x
        // patch AFTER a 2.x release exists. Selection must be purely by semver value, ignoring
        // publish order entirely (this fake doesn't even carry created_at, by design: order in the
        // input list must not matter).
        GithubReleaseLatestSource source = sourceWith(
                release("v2.5.0", "older-list-position", false, false),
                release("v1.9.9", "newer-list-position-but-lower-version", false, false));

        Version result = source.version();

        assertEquals("2.5.0", result.value(),
                "the largest semver must win regardless of publish recency / list position");
    }

    @Test
    void version_skipsAnUnparseableTagName_withoutFailingTheRead() {
        GithubReleaseLatestSource source = sourceWith(
                release("not-a-semver", "n", false, false),
                release("v1.4.0", "n", false, false));

        Version result = source.version();

        assertEquals("1.4.0", result.value(), "an unparseable tag_name must be skipped, not crash the read");
    }

    @Test
    void version_throws_whenNoReleaseSurvivesFiltering_allPrereleaseOrDraft() {
        GithubReleaseLatestSource source = sourceWith(
                release("v9.0.0", "n", true, false),
                release("v8.0.0", "n", false, true));

        assertThrows(RuntimeException.class, source::version,
                "no release surviving the prerelease/draft filter is a per-app scrape failure, like any "
                        + "other empty upstream read");
    }

    @Test
    void version_throws_whenTheReleaseListIsEmpty() {
        GithubReleaseLatestSource source = sourceWith();

        assertThrows(RuntimeException.class, source::version);
    }

    @Test
    void version_throws_whenEveryTagNameIsUnparseable() {
        GithubReleaseLatestSource source = sourceWith(
                release("not-a-semver", "n", false, false),
                release("also-not-a-semver", "n", false, false));

        assertThrows(RuntimeException.class, source::version,
                "if every survivor's tag_name is unparseable, none survive selection — must throw, "
                        + "not silently return null/garbage");
    }

    @Test
    void version_treatsDuplicateOrTiedVersions_asHarmless() {
        GithubReleaseLatestSource source = sourceWith(
                release("v1.0.0", "n", false, false),
                release("v1.0.0", "n", false, false),
                release("v0.9.0", "n", false, false));

        Version result = source.version();

        assertEquals("1.0.0", result.value());
    }

    // --- fakes ------------------------------------------------------------------------------------

    private static GithubReleaseLatestSource sourceWith(GithubReleaseResponseDTO... releases) {
        List<GithubReleaseResponseDTO> fixed = List.of(releases);
        // Fake GithubReleaseClient as a lambda (port-level seam, not a mock-the-concrete-type
        // shortcut): a lambda is not a CDI-discoverable bean class, sidestepping Quarkus Arc's
        // classpath bean-scanning (which would otherwise try to satisfy a fake's constructor
        // parameters via injection — see GithubReleaseClient's single abstract method `releases(int)`,
        // which makes it a valid lambda target despite taking an argument). Ignores `perPage` —
        // pagination/per_page-wiring is an integration-level concern (GithubReleaseLatestSourceIT).
        return new GithubReleaseLatestSource(perPage -> fixed);
    }

    private static GithubReleaseResponseDTO release(
            String tagName, String name, boolean prerelease, boolean draft) {
        GithubReleaseResponseDTO dto = new GithubReleaseResponseDTO();
        dto.tagName = tagName;
        dto.name = name;
        dto.prerelease = prerelease;
        dto.draft = draft;
        return dto;
    }
}
