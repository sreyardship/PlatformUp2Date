package org.yardship.unit.adapters.out.versionsource.latest.ociregistry;

import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.latest.ociregistry.OciTagSelector;
import org.yardship.adapters.out.versionsource.latest.ociregistry.TagSelection;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import org.yardship.core.domain.primitives.VersionValue;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link OciTagSelector} — the pure tag-selection collaborator extracted from
 * {@code OciRegistryLatestSource}. All selection logic (largest-clean-semver, prerelease-filter
 * exact-match, strip-prerelease, error paths) is owned here and exercised against in-memory tag
 * lists — no HTTP, no WireMock.
 *
 * <p>Coverage:
 * <ul>
 *   <li>No filter — largest clean semver wins; non-semver and prerelease variant tags are skipped.</li>
 *   <li>No filter — single clean tag is returned as-is.</li>
 *   <li>No filter — all-skipped tag set throws.</li>
 *   <li>Filter — largest tag whose prerelease segment EXACTLY equals the filter wins (full tag
 *       reported).</li>
 *   <li>Filter — exact match: {@code alpine} does NOT match {@code alpine3.16}.</li>
 *   <li>Filter — {@code alpine3.16} matches {@code alpine3.16} but not bare {@code alpine}.</li>
 *   <li>Filter — clean tags are skipped even when numerically larger than any matching tag.</li>
 *   <li>Filter — no matching tag throws.</li>
 *   <li>Filter + strip — selection ranks by full tag value; reported result has prerelease stripped.</li>
 *   <li>Filter + strip — ranking uses full tag, so the largest full tag wins before stripping.</li>
 *   <li>Dotnet parser — the selector is scheme-agnostic: it selects the largest tag under the
 *       injected parser's own comparison, and {@code strip-prerelease} is a harmless no-op there
 *       (ADR-0036). A dotnet app can never reach the filter path — that combination is refused at
 *       startup by {@code VersionParsers}, covered in {@code VersionParsersTests}.</li>
 * </ul>
 */
class OciTagSelectorTests {

    private static final VersionParser PARSER = new VersionParser(VersionScheme.SEMVER);

    // --- factory helpers -----------------------------------------------------------------------

    private static final String REGISTRY_CONTEXT = "https://registry.example/v2/library/app";

    private static OciTagSelector selectorNoFilter() {
        return new OciTagSelector(new TagSelection(100, 1000, Optional.empty(), false, Optional.empty()), PARSER, REGISTRY_CONTEXT);
    }

    private static OciTagSelector selectorWithFilter(String filter) {
        return new OciTagSelector(new TagSelection(100, 1000, Optional.of(filter), false, Optional.empty()), PARSER, REGISTRY_CONTEXT);
    }

    private static OciTagSelector selectorWithFilterAndStrip(String filter) {
        return new OciTagSelector(new TagSelection(100, 1000, Optional.of(filter), true, Optional.empty()), PARSER, REGISTRY_CONTEXT);
    }

    private static final VersionParser DOTNET_PARSER = new VersionParser(VersionScheme.DOTNET);

    private static OciTagSelector dotnetSelectorNoFilter(boolean stripPrerelease) {
        return new OciTagSelector(
                new TagSelection(100, 1000, Optional.empty(), stripPrerelease, Optional.empty()),
                DOTNET_PARSER,
                REGISTRY_CONTEXT);
    }

    // --- no-filter: clean semver selection ----------------------------------------------------

    @Test
    void select_noFilter_largestCleanSemverWins() {
        // Arrange
        List<String> tags = List.of("1.24.0", "1.25.3", "1.23.0");

        // Act
        VersionValue result = selectorNoFilter().select(tags);

        // Assert
        assertEquals("1.25.3", result.value(), "largest clean semver must win");
    }

    @Test
    void select_noFilter_nonSemverAndPrereleaseVariantTagsSkipped() {
        // Arrange — includes non-semver ("latest", "stable", "edge") and prerelease variant tags
        // ("7.0.0-alpine", "7.2.0-rc1"); only clean semver "7.0.0" and "7.2.0" are eligible.
        List<String> tags = List.of("7.0.0", "7.0.0-alpine", "7.2.0-rc1", "latest", "7.2.0", "edge");

        // Act
        VersionValue result = selectorNoFilter().select(tags);

        // Assert
        assertEquals("7.2.0", result.value(),
                "7.2.0 is the largest clean semver; variant and non-semver tags are skipped");
    }

    @Test
    void select_noFilter_singleCleanTag_returnsIt() {
        // Arrange
        List<String> tags = List.of("3.18.0");

        // Act
        VersionValue result = selectorNoFilter().select(tags);

        // Assert
        assertEquals("3.18.0", result.value());
    }

    @Test
    void select_noFilter_allTagsSkipped_throws() {
        // Arrange — only non-semver and prerelease variant tags; no clean semver survives
        List<String> tags = List.of("latest", "edge", "1.0.0-alpine");

        // Act + Assert
        assertThrows(IllegalStateException.class, () -> selectorNoFilter().select(tags),
                "an all-skipped tag set must throw as a per-app scrape failure");
    }

    // --- prerelease-filter: exact-match selection ---------------------------------------------

    @Test
    void select_withAlpineFilter_largestAlpineTagWins_andFullTagValueReported() {
        // Arrange — clean "1.0.0", both alpine variants, a non-alpine variant, and "latest";
        // filter=alpine → only "1.20.0-alpine" and "1.22.0-alpine" qualify; largest wins.
        List<String> tags = List.of("1.0.0", "1.20.0-alpine", "1.22.0-alpine",
                "1.22.0-alpine3.16", "1.22.0-debian", "latest");

        // Act
        VersionValue result = selectorWithFilter("alpine").select(tags);

        // Assert
        assertEquals("1.22.0-alpine", result.value(),
                "filter=alpine must report the largest -alpine tag as its full value");
    }

    @Test
    void select_withAlpineFilter_exactMatchOnly_notAlpine316() {
        // Arrange — "1.22.0-alpine3.16" must NOT satisfy filter="alpine" (exact, not prefix);
        // only "1.20.0-alpine" matches.
        List<String> tags = List.of("1.20.0-alpine", "1.22.0-alpine3.16", "latest");

        // Act
        VersionValue result = selectorWithFilter("alpine").select(tags);

        // Assert
        assertEquals("1.20.0-alpine", result.value(),
                "filter=alpine must not match 1.22.0-alpine3.16 (exact, not prefix)");
    }

    @Test
    void select_withAlpine316Filter_selectsAlpine316_andNotBareAlpine() {
        // Arrange — filter="alpine3.16" selects "1.22.0-alpine3.16" but NOT "1.20.0-alpine".
        List<String> tags = List.of("1.20.0-alpine", "1.22.0-alpine3.16", "1.21.0-alpine3.16");

        // Act
        VersionValue result = selectorWithFilter("alpine3.16").select(tags);

        // Assert
        assertEquals("1.22.0-alpine3.16", result.value(),
                "filter=alpine3.16 selects the largest -alpine3.16 tag");
    }

    @Test
    void select_withFilter_cleanTagsSkipped_evenWhenLarger() {
        // Arrange — clean tag "2.0.0" is numerically larger than any "-alpine" tag,
        // but with a filter set, clean tags must be skipped.
        List<String> tags = List.of("2.0.0", "1.22.0-alpine", "1.20.0-alpine");

        // Act
        VersionValue result = selectorWithFilter("alpine").select(tags);

        // Assert
        assertEquals("1.22.0-alpine", result.value(),
                "filter=alpine: clean 2.0.0 is ignored even though numerically larger");
    }

    @Test
    void select_withFilter_noMatchingTag_throws() {
        // Arrange — registry has only clean and debian tags; no -alpine tags.
        List<String> tags = List.of("1.22.0", "1.22.0-debian", "latest");

        // Act + Assert
        assertThrows(IllegalStateException.class,
                () -> selectorWithFilter("alpine").select(tags),
                "no tag matches filter=alpine → must throw as a per-app scrape failure");
    }

    // --- strip-prerelease ---------------------------------------------------------------------

    @Test
    void select_withFilterAndStrip_reportsStrippedVersion() {
        // Arrange — filter=alpine + strip=true:
        //   SELECTION: picks the largest -alpine tag → "1.22.0-alpine" (full tag, correct ranking)
        //   REPORT:    strips the prerelease → "1.22.0"
        List<String> tags = List.of("1.0.0", "1.20.0-alpine", "1.22.0-alpine",
                "1.22.0-alpine3.16", "1.22.0-debian", "latest");

        // Act
        VersionValue result = selectorWithFilterAndStrip("alpine").select(tags);

        // Assert
        assertEquals("1.22.0", result.value(),
                "filter=alpine strip=true: selection picks 1.22.0-alpine, stripped to 1.22.0");
    }

    @Test
    void select_withFilterAndStrip_ranksByFullTag_thenStrips() {
        // Arrange — ranking must be by FULL tag (1.24.0-alpine > 1.22.0-alpine by core version);
        // only the reported result is stripped → answer is 1.24.0, not 1.22.0.
        List<String> tags = List.of("1.20.0-alpine", "1.24.0-alpine", "1.22.0-alpine");

        // Act
        VersionValue result = selectorWithFilterAndStrip("alpine").select(tags);

        // Assert
        assertEquals("1.24.0", result.value(),
                "filter=alpine strip=true: 1.24.0-alpine is the largest, reported stripped as 1.24.0");
    }

    // --- dotnet scheme (ADR-0036) --------------------------------------------------------------

    @Test
    void select_noFilter_dotnetParser_largestTagWins() {
        // Four-component tags no semver parser could read; unparseable tags are skipped as ever.
        List<String> tags = List.of("v4.0.17.2952", "v4.0.20.3014", "v4.0.9.2100", "latest");

        VersionValue result = dotnetSelectorNoFilter(false).select(tags);

        assertEquals("4.0.20.3014", result.value(),
                "largest tag under the dotnet comparison must win, with the leading v stripped");
    }

    @Test
    void select_noFilter_dotnetParser_stripPrereleaseIsASilentNoOp() {
        // A dotnet version has no pre-release segment to clear, so strip-prerelease changes nothing
        // and must not error.
        List<String> tags = List.of("v4.0.17.2952", "v4.0.20.3014");

        VersionValue result = dotnetSelectorNoFilter(true).select(tags);

        assertEquals("4.0.20.3014", result.value());
    }

    // --- semver-optional-patch (ADR-0041) ------------------------------------------------------

    private static final VersionParser OPTIONAL_PATCH_PARSER = VersionParser.semver(true);

    private static final List<String> JELLYFIN_SHAPED_TAGS =
            List.of("v12.2", "v12.0-rc7", "12", "unstable", "2026101212", "latest");

    @Test
    void select_noFilter_optionalPatchParser_selectsTheTwoPartTag() {
        OciTagSelector selector = new OciTagSelector(
                new TagSelection(100, 1000, Optional.empty(), false, Optional.empty()),
                OPTIONAL_PATCH_PARSER, REGISTRY_CONTEXT);

        VersionValue result = selector.select(JELLYFIN_SHAPED_TAGS);

        assertEquals("12.2", result.value(),
                "the two-part prerelease is skipped and the floating '12' is never a version");
    }

    @Test
    void select_noFilter_strictParser_throwsForTheSameTwoPartTags() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> selectorNoFilter().select(JELLYFIN_SHAPED_TAGS));

        assertTrue(ex.getMessage().contains("No clean semver tag found"),
                "strict semver keeps today's 'no clean semver tag' failure; was: " + ex.getMessage());
    }

    @Test
    void select_withFilterAndStrip_optionalPatchParser_matchesAndStripsATwoPartVariantTag() {
        OciTagSelector selector = new OciTagSelector(
                new TagSelection(100, 1000, Optional.of("alpine"), true, Optional.empty()),
                OPTIONAL_PATCH_PARSER, REGISTRY_CONTEXT);

        VersionValue result = selector.select(List.of("12.1-alpine", "12.2-alpine", "12.2", "12"));

        assertEquals("12.2", result.value(),
                "12.2-alpine matches the filter and is reported stripped, in its published shape");
    }

    // --- regex: tag-suffix extraction (issue #93) ----------------------------------------------

    private static final String LINUXSERVER_DOTNET_REGEX = "^(\\d+(?:\\.\\d+){1,3})-ls\\d+$";

    private static OciTagSelector selectorWithRegex(String regex, VersionParser parser) {
        return new OciTagSelector(
                new TagSelection(100, 1000, Optional.empty(), false, Optional.of(regex)),
                parser, REGISTRY_CONTEXT);
    }

    @Test
    void select_withRegex_linuxserverTags_largestCapturedVersionWins() {
        List<String> tags = List.of(
                "latest", "develop", "version-4.0.15.2941", "4.0.14.2939-ls289",
                "4.0.15.2941-ls290", "amd64-4.0.15.2941-ls290", "4.0.9.2100-ls250");

        VersionValue result = selectorWithRegex(LINUXSERVER_DOTNET_REGEX, DOTNET_PARSER).select(tags);

        assertEquals("4.0.15.2941", result.value(),
                "capture group 1 is the version; the -lsN build suffix is not part of it");
    }

    @Test
    void select_withRegex_nonMatchingTagsAreSkipped_evenWhenTheyParseLarger() {
        List<String> tags = List.of("1.2.3-ls10", "9.9.9");

        VersionValue result = selectorWithRegex("^(\\d+\\.\\d+\\.\\d+)-ls\\d+$", PARSER).select(tags);

        assertEquals("1.2.3", result.value());
    }

    @Test
    void select_withRegex_noTagMatches_throwsNamingTheRegex() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> selectorWithRegex(LINUXSERVER_DOTNET_REGEX, DOTNET_PARSER)
                        .select(List.of("latest", "4.0.15.2941")));

        assertTrue(ex.getMessage().contains("regex"),
                "the failure must point at the configured regex; was: " + ex.getMessage());
    }

    @Test
    void select_withRegexAndFilter_filterAppliesToTheCapturedVersion() {
        OciTagSelector selector = new OciTagSelector(
                new TagSelection(100, 1000, Optional.of("alpine"), false, Optional.of("^(.+)-ls\\d+$")),
                PARSER, REGISTRY_CONTEXT);

        VersionValue result = selector.select(List.of("1.2.3-alpine-ls5", "1.3.0-ls6", "1.1.0-alpine-ls4"));

        assertEquals("1.2.3-alpine", result.value());
    }

    @Test
    void construct_withRegexWithoutCaptureGroup_throwsIllegalArgument() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> selectorWithRegex("\\d+-ls\\d+", PARSER));

        assertTrue(ex.getMessage().contains("'oci-registry' latest source's 'regex'"),
                "was: " + ex.getMessage());
    }
}
