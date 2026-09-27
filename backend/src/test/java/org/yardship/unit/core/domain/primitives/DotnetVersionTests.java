package org.yardship.unit.core.domain.primitives;

import org.junit.jupiter.api.Test;
import org.yardship.core.domain.exceptions.InvalidVersionException;
import org.yardship.core.domain.primitives.DotnetVersion;
import org.yardship.core.domain.primitives.SemverVersion;
import org.yardship.core.domain.primitives.VersionScheme;
import org.yardship.core.domain.primitives.VersionValue;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behavior suite for {@link DotnetVersion}, the {@code System.Version}-shaped {@link VersionValue}
 * used by Sonarr/Radarr/Prowlarr-style four-part versions.
 *
 * <p>Covers the grammar (two to four numeric components, optional leading {@code v}), display
 * fidelity, absent-trailing-components-are-zero equality, major/minor/build ordering with the
 * revision deliberately NOT compared, the {@code dotnet-compare-build} knob, drift grading, and the
 * always-empty pre-release contract.
 *
 * <p>Covered API surface:
 * <ul>
 *   <li>{@code DotnetVersion(String input)} — compares the build component (the default).</li>
 *   <li>{@code DotnetVersion(String input, boolean compareBuild)} — {@code false} drops the build
 *       from comparison, leaving a major.minor comparison.</li>
 *   <li>{@code String value()} — the original string verbatim, minus a stripped leading {@code v}.
 *       Never padded to four components.</li>
 *   <li>{@code boolean isOlderThan(VersionValue)} — numeric major, then minor, then (by default)
 *       build. The revision is never compared.</li>
 *   <li>{@code VersionValue.Diff diff(VersionValue)} — most significant differing component wins:
 *       major → MAJOR, minor → MINOR, build → PATCH. The revision cannot produce a grade.</li>
 *   <li>{@code major()}/{@code minor()}/{@code build()}/{@code revision()} — component accessors,
 *       absent components rendering as {@code "0"} (consumed by {@code ChangelogTemplate}).</li>
 *   <li>{@code preReleaseSegment()} — always empty; {@code withoutPreRelease()} — returns
 *       {@code this}.</li>
 * </ul>
 *
 * <p>This is a pure domain unit test — no Quarkus context needed.
 */
public class DotnetVersionTests {

    // -----------------------------------------------------------------------
    // Display fidelity
    // -----------------------------------------------------------------------

    @Test
    void value_returnsOriginalStringVerbatim() {
        assertEquals("4.0.20.3014", new DotnetVersion("4.0.20.3014").value(),
                "value() must return the original string exactly — no normalisation");
    }

    @Test
    void value_stripsLeadingV() {
        assertEquals("4.0.20.3014", new DotnetVersion("v4.0.20.3014").value());
        assertEquals("4.0.20.3014", new DotnetVersion("V4.0.20.3014").value());
    }

    @Test
    void value_neverPadsToFourComponents() {
        assertEquals("6.2", new DotnetVersion("6.2").value(),
                "A two-component version must display as written, never padded to 6.2.0.0");
        assertEquals("6.2.1", new DotnetVersion("6.2.1").value());
    }

    @Test
    void scheme_isDotnet() {
        assertEquals(VersionScheme.DOTNET, new DotnetVersion("6.2").scheme());
    }

    // -----------------------------------------------------------------------
    // Grammar — accepted
    // -----------------------------------------------------------------------

    @Test
    void constructor_acceptsTwoToFourComponents() {
        assertDoesNotThrow(() -> new DotnetVersion("6.2"));
        assertDoesNotThrow(() -> new DotnetVersion("6.2.1"));
        assertDoesNotThrow(() -> new DotnetVersion("6.2.1.10461"));
    }

    @Test
    void constructor_trimsSurroundingWhitespace() {
        assertEquals("6.2.1", new DotnetVersion("  6.2.1  ").value());
    }

    // -----------------------------------------------------------------------
    // Grammar — rejected (never a boot failure: InvalidVersionException degrades one scrape)
    // -----------------------------------------------------------------------

    @Test
    void constructor_rejectsSingleComponent() {
        assertThrows(InvalidVersionException.class, () -> new DotnetVersion("4"),
                "System.Version requires at least major.minor");
    }

    @Test
    void constructor_rejectsFiveComponents() {
        assertThrows(InvalidVersionException.class, () -> new DotnetVersion("1.2.3.4.5"));
    }

    @Test
    void constructor_rejectsNonNumericComponent() {
        assertThrows(InvalidVersionException.class, () -> new DotnetVersion("4.0.x"));
    }

    @Test
    void constructor_rejectsPrereleaseSuffix() {
        assertThrows(InvalidVersionException.class, () -> new DotnetVersion("4.0.17-rc1"),
                "System.Version has no pre-release concept");
    }

    @Test
    void constructor_rejectsNegativeComponent() {
        assertThrows(InvalidVersionException.class, () -> new DotnetVersion("-1.0"));
    }

    @Test
    void constructor_rejectsNull() {
        assertThrows(InvalidVersionException.class, () -> new DotnetVersion(null));
    }

    @Test
    void constructor_rejectsEmptyAndTrailingSeparator() {
        assertThrows(InvalidVersionException.class, () -> new DotnetVersion(""));
        assertThrows(InvalidVersionException.class, () -> new DotnetVersion("4.0."));
    }

    @Test
    void constructor_rejectsComponentTooLargeForAnInt() {
        assertThrows(InvalidVersionException.class, () -> new DotnetVersion("1.99999999999"),
                "System.Version components are 32-bit signed integers");
    }

    // -----------------------------------------------------------------------
    // Ordering — the motivating Sonarr/Radarr/Prowlarr cases
    // -----------------------------------------------------------------------

    @Test
    void isOlderThan_comparesBuildNumerically_sonarrBehind() {
        DotnetVersion current = new DotnetVersion("4.0.17.2952");
        DotnetVersion latest = new DotnetVersion("v4.0.20.3014");

        assertTrue(current.isOlderThan(latest), "4.0.17.2952 must be older than 4.0.20.3014");
        assertFalse(latest.isOlderThan(current));
    }

    @Test
    void isOlderThan_ignoresRevision_sonarrUpToDate() {
        DotnetVersion current = new DotnetVersion("4.0.20.3012");
        DotnetVersion latest = new DotnetVersion("v4.0.20.3014");

        assertFalse(current.isOlderThan(latest),
                "A revision bump is not drift — System.Version calls such builds interchangeable");
        assertFalse(latest.isOlderThan(current));
    }

    @Test
    void isOlderThan_comparesMinor_radarrBehind() {
        assertTrue(new DotnetVersion("6.2.1.10461").isOlderThan(new DotnetVersion("v6.4.4.10685")));
    }

    @Test
    void isOlderThan_comparesMinor_prowlarrBehind() {
        assertTrue(new DotnetVersion("2.4.0.5397").isOlderThan(new DotnetVersion("v2.6.5.5623")));
    }

    @Test
    void isOlderThan_comparesMajorFirst() {
        assertTrue(new DotnetVersion("1.9.9.9").isOlderThan(new DotnetVersion("2.0.0.0")));
        assertFalse(new DotnetVersion("2.0.0.0").isOlderThan(new DotnetVersion("1.9.9.9")));
    }

    @Test
    void isOlderThan_comparesComponentsNumericallyNotLexically() {
        assertTrue(new DotnetVersion("4.0.9.1").isOlderThan(new DotnetVersion("4.0.20.1")),
                "build 9 < build 20 numerically, even though \"9\" > \"20\" lexically");
    }

    // -----------------------------------------------------------------------
    // Absent trailing components count as zero
    // -----------------------------------------------------------------------

    @Test
    void absentTrailingComponents_countAsZero_forOrdering() {
        DotnetVersion twoPart = new DotnetVersion("6.2");
        DotnetVersion threePart = new DotnetVersion("6.2.0");
        DotnetVersion fourPart = new DotnetVersion("6.2.0.0");

        assertFalse(twoPart.isOlderThan(threePart), "6.2 and 6.2.0 must compare equal");
        assertFalse(threePart.isOlderThan(twoPart));
        assertFalse(threePart.isOlderThan(fourPart), "6.2.0 and 6.2.0.0 must compare equal");
        assertFalse(fourPart.isOlderThan(threePart));
        assertFalse(twoPart.isOlderThan(fourPart));
        assertFalse(fourPart.isOlderThan(twoPart));
    }

    @Test
    void absentTrailingComponents_countAsZero_forEqualsAndHashCode() {
        DotnetVersion twoPart = new DotnetVersion("6.2");
        DotnetVersion threePart = new DotnetVersion("6.2.0");
        DotnetVersion fourPart = new DotnetVersion("6.2.0.0");

        assertEquals(twoPart, threePart);
        assertEquals(threePart, fourPart);
        assertEquals(twoPart, fourPart);
        assertEquals(twoPart.hashCode(), threePart.hashCode());
        assertEquals(threePart.hashCode(), fourPart.hashCode());
    }

    @Test
    void absentTrailingComponents_doNotMakeAVersionOlderThanItsPaddedForm() {
        // Deliberate divergence from System.Version, which orders 1.1 < 1.1.0 < 1.1.0.0 to
        // distinguish assembly identities. PU2D compares published releases, where writing
        // fewer digits is formatting, not precedence.
        assertEquals(VersionValue.Diff.NONE, new DotnetVersion("1.1").diff(new DotnetVersion("1.1.0.0")));
    }

    // -----------------------------------------------------------------------
    // equals / hashCode are comparison-consistent (the revision is ignored)
    // -----------------------------------------------------------------------

    @Test
    void equals_ignoresRevision_soEqualComparisonMeansEqualValue() {
        DotnetVersion a = new DotnetVersion("4.0.20.3012");
        DotnetVersion b = new DotnetVersion("4.0.20.3014");

        assertEquals(a, b, "Two versions that compare equal must be equal");
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void equals_returnsFalse_whenComparedComponentsDiffer() {
        assertNotEquals(new DotnetVersion("4.0.17.1"), new DotnetVersion("4.0.20.1"));
        assertNotEquals(new DotnetVersion("4.0.17.1"), new DotnetVersion("4.1.17.1"));
        assertNotEquals(new DotnetVersion("4.0.17.1"), new DotnetVersion("5.0.17.1"));
    }

    @Test
    void equals_returnsFalse_forAForeignVersionValue() {
        assertNotEquals(new DotnetVersion("1.2.3"), new SemverVersion("1.2.3"));
    }

    @Test
    void equals_returnsFalse_whenCompareBuildDiffers() {
        // The two instances grade and order the same input differently, so they are not the same
        // value. In production a single per-app parser makes a mixed pair unreachable.
        assertNotEquals(new DotnetVersion("4.0.17", true), new DotnetVersion("4.0.17", false));
    }

    // -----------------------------------------------------------------------
    // Drift grading
    // -----------------------------------------------------------------------

    @Test
    void diff_gradesMajor_whenMajorDiffers() {
        assertEquals(VersionValue.Diff.MAJOR,
                new DotnetVersion("4.0.20.3014").diff(new DotnetVersion("5.0.20.3014")));
    }

    @Test
    void diff_gradesMinor_whenMinorDiffers() {
        assertEquals(VersionValue.Diff.MINOR,
                new DotnetVersion("6.2.1.10461").diff(new DotnetVersion("6.4.4.10685")));
    }

    @Test
    void diff_gradesPatch_whenOnlyBuildDiffers() {
        assertEquals(VersionValue.Diff.PATCH,
                new DotnetVersion("4.0.17.2952").diff(new DotnetVersion("4.0.20.3014")));
    }

    @Test
    void diff_gradesNone_whenOnlyRevisionDiffers() {
        assertEquals(VersionValue.Diff.NONE,
                new DotnetVersion("4.0.20.3012").diff(new DotnetVersion("4.0.20.3014")),
                "The revision cannot produce a grade");
    }

    @Test
    void diff_gradesMostSignificantDifferingComponent() {
        assertEquals(VersionValue.Diff.MAJOR,
                new DotnetVersion("1.2.3.4").diff(new DotnetVersion("2.9.9.9")));
    }

    @Test
    void diff_gradesNone_whenVersionsAreEqual() {
        assertEquals(VersionValue.Diff.NONE,
                new DotnetVersion("6.2").diff(new DotnetVersion("6.2.0")));
    }

    // -----------------------------------------------------------------------
    // dotnet-compare-build: false
    // -----------------------------------------------------------------------

    @Test
    void compareBuildFalse_makesDifferingBuildsEqual() {
        DotnetVersion current = new DotnetVersion("4.0.17", false);
        DotnetVersion latest = new DotnetVersion("4.0.20", false);

        assertFalse(current.isOlderThan(latest), "With the build dropped, 4.0.17 and 4.0.20 are equal");
        assertFalse(latest.isOlderThan(current));
        assertEquals(current, latest);
        assertEquals(current.hashCode(), latest.hashCode());
    }

    @Test
    void compareBuildFalse_canNeverGradePatch() {
        assertEquals(VersionValue.Diff.NONE,
                new DotnetVersion("4.0.17", false).diff(new DotnetVersion("4.0.20", false)),
                "Expected consequence of dropping the build, not a bug");
    }

    @Test
    void compareBuildFalse_stillComparesMajorAndMinor() {
        assertTrue(new DotnetVersion("4.0.99", false).isOlderThan(new DotnetVersion("4.1.0", false)));
        assertTrue(new DotnetVersion("4.9.99", false).isOlderThan(new DotnetVersion("5.0.0", false)));
        assertEquals(VersionValue.Diff.MINOR,
                new DotnetVersion("4.0.99", false).diff(new DotnetVersion("4.1.0", false)));
    }

    @Test
    void compareBuildDefault_comparesTheBuild() {
        assertTrue(new DotnetVersion("4.0.17").isOlderThan(new DotnetVersion("4.0.20")),
                "The default is on: 4.0.17 -> 4.0.20 is the motivating Sonarr case");
    }

    // -----------------------------------------------------------------------
    // Component accessors (changelog placeholders)
    // -----------------------------------------------------------------------

    @Test
    void componentAccessors_returnEachComponent() {
        DotnetVersion version = new DotnetVersion("4.0.17.2952");

        assertEquals("4", version.major());
        assertEquals("0", version.minor());
        assertEquals("17", version.build());
        assertEquals("2952", version.revision());
    }

    @Test
    void componentAccessors_renderAbsentTrailingComponentsAsZero() {
        DotnetVersion version = new DotnetVersion("6.2");

        assertEquals("6", version.major());
        assertEquals("2", version.minor());
        assertEquals("0", version.build());
        assertEquals("0", version.revision());
    }

    // -----------------------------------------------------------------------
    // Pre-release — System.Version has no such concept
    // -----------------------------------------------------------------------

    @Test
    void preReleaseSegment_isAlwaysEmpty() {
        assertTrue(new DotnetVersion("4.0.20.3014").preReleaseSegment().isEmpty());
    }

    @Test
    void withoutPreRelease_returnsTheSameInstance() {
        DotnetVersion version = new DotnetVersion("4.0.20.3014");

        assertSame(version, version.withoutPreRelease(),
                "There is no pre-release segment to clear, so strip-prerelease is a no-op");
    }

    // -----------------------------------------------------------------------
    // Cross-scheme comparison guard (unreachable in production — one parser per app)
    // -----------------------------------------------------------------------

    @Test
    void isOlderThan_throws_whenComparedWithAForeignScheme() {
        DotnetVersion dotnet = new DotnetVersion("1.2.3");

        assertThrows(IllegalArgumentException.class, () -> dotnet.isOlderThan(new SemverVersion("1.2.4")));
        assertThrows(IllegalArgumentException.class, () -> dotnet.diff(new SemverVersion("1.2.4")));
    }

    @Test
    void toString_returnsTheDisplayedValue() {
        assertEquals("4.0.20.3014", new DotnetVersion("v4.0.20.3014").toString());
    }
}
