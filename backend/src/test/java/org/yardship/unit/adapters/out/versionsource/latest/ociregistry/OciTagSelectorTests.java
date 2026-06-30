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
 * </ul>
 */
class OciTagSelectorTests {

    private static final VersionParser PARSER = new VersionParser(VersionScheme.SEMVER);

    // --- factory helpers -----------------------------------------------------------------------

    private static final String REGISTRY_CONTEXT = "https://registry.example/v2/library/app";

    private static OciTagSelector selectorNoFilter() {
        return new OciTagSelector(new TagSelection(100, 1000, Optional.empty(), false), PARSER, REGISTRY_CONTEXT);
    }

    private static OciTagSelector selectorWithFilter(String filter) {
        return new OciTagSelector(new TagSelection(100, 1000, Optional.of(filter), false), PARSER, REGISTRY_CONTEXT);
    }

    private static OciTagSelector selectorWithFilterAndStrip(String filter) {
        return new OciTagSelector(new TagSelection(100, 1000, Optional.of(filter), true), PARSER, REGISTRY_CONTEXT);
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
}
