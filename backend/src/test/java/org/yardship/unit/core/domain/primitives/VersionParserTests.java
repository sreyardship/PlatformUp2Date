package org.yardship.unit.core.domain.primitives;

import org.junit.jupiter.api.Test;
import org.yardship.core.domain.exceptions.InvalidVersionException;
import org.yardship.core.domain.primitives.CalverVersion;
import org.yardship.core.domain.primitives.SemverVersion;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import org.yardship.core.domain.primitives.VersionValue;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link VersionParser}: the per-app domain service that translates a raw version
 * string into the appropriate {@link VersionValue} implementation. One parser is built per app and
 * shared by both the current and latest legs, so they always produce commensurable versions.
 *
 * <p>This is a pure domain unit test — no Quarkus context needed.
 *
 * <p>Assumed API surface for the implementer (slice 02 additions in bold):
 * <ul>
 *   <li>{@code VersionParser(VersionScheme scheme)} — single-arg constructor; works for SEMVER;
 *       throws {@link IllegalArgumentException} at construction for CALVER (no format supplied).</li>
 *   <li><b>{@code VersionParser(VersionScheme scheme, String calverFormat)}</b> — two-arg
 *       constructor for CALVER; throws {@link IllegalArgumentException} at construction when
 *       {@code calverFormat} is null, blank, or contains an unknown token.</li>
 *   <li>{@code VersionValue parse(String raw)} — SEMVER returns {@link SemverVersion};
 *       <b>CALVER now returns {@link CalverVersion}</b>;
 *       throws {@link InvalidVersionException} when {@code raw} does not match the format.</li>
 * </ul>
 */
public class VersionParserTests {

    // -----------------------------------------------------------------------
    // SEMVER parser — happy paths
    // -----------------------------------------------------------------------

    @Test
    void semverParser_parsesRawString_intoSemverVersion() {
        // Arrange
        VersionParser parser = new VersionParser(VersionScheme.SEMVER);

        // Act
        VersionValue result = parser.parse("1.2.3");

        // Assert
        assertInstanceOf(SemverVersion.class, result,
                "A SEMVER parser must return a SemverVersion instance");
        assertEquals("1.2.3", result.value());
    }

    @Test
    void semverParser_stripsLeadingVPrefix_beforeParsing() {
        // Arrange
        VersionParser parser = new VersionParser(VersionScheme.SEMVER);

        // Act
        VersionValue lowerV = parser.parse("v1.4.0");
        VersionValue upperV = parser.parse("V2.0.0");

        // Assert
        assertEquals("1.4.0", lowerV.value());
        assertEquals("2.0.0", upperV.value());
    }

    @Test
    void semverParser_isSingleInstance_sharedByBothLegs() {
        // The same parser instance must be usable multiple times (current + latest leg).
        // Arrange
        VersionParser parser = new VersionParser(VersionScheme.SEMVER);

        // Act — simulate current-leg parse followed by latest-leg parse
        VersionValue current = parser.parse("1.0.0");
        VersionValue latest = parser.parse("2.0.0");

        // Assert — both parses succeed and are commensurable (can be compared)
        assertEquals("1.0.0", current.value());
        assertEquals("2.0.0", latest.value());
        assertTrue(current.isOlderThan(latest),
                "Versions from the same parser must be mutually comparable");
    }

    @Test
    void semverParser_parsedVersions_supportDiff() {
        // Arrange
        VersionParser parser = new VersionParser(VersionScheme.SEMVER);

        // Act
        VersionValue older = parser.parse("1.0.0");
        VersionValue newer = parser.parse("2.0.0");

        // Assert
        assertEquals(VersionValue.Diff.MAJOR, older.diff(newer));
    }

    @Test
    void semverParser_parsedVersion_supportsWithoutPreRelease() {
        // Arrange
        VersionParser parser = new VersionParser(VersionScheme.SEMVER);

        // Act
        VersionValue versioned = parser.parse("2.11.1-6b7ecba1");
        VersionValue stripped = versioned.withoutPreRelease();

        // Assert
        assertEquals("2.11.1", stripped.value());
        assertEquals("2.11.1-6b7ecba1", versioned.value(),
                "withoutPreRelease must leave the original unchanged");
    }

    // -----------------------------------------------------------------------
    // SEMVER parser — error paths
    // -----------------------------------------------------------------------

    @Test
    void semverParser_throwsInvalidVersionException_forNullInput() {
        // Arrange
        VersionParser parser = new VersionParser(VersionScheme.SEMVER);

        // Act & Assert
        assertThrows(InvalidVersionException.class,
                () -> parser.parse(null),
                "A null raw version string must surface as InvalidVersionException");
    }

    @Test
    void semverParser_throwsInvalidVersionException_forMalformedInput() {
        // Arrange
        VersionParser parser = new VersionParser(VersionScheme.SEMVER);

        // Act & Assert — same invalid inputs the SemverVersion constructor rejects
        assertThrows(InvalidVersionException.class, () -> parser.parse("not-a-version"));
        assertThrows(InvalidVersionException.class, () -> parser.parse("<div>Bad Version</div>"));
        assertThrows(InvalidVersionException.class, () -> parser.parse("; DROP TABLES"));
    }

    // -----------------------------------------------------------------------
    // CALVER parser — happy paths (slice 02)
    // -----------------------------------------------------------------------

    @Test
    void calverParser_parsesRawString_intoCalverVersion() {
        // Arrange
        VersionParser parser = new VersionParser(VersionScheme.CALVER, "YYYY.0M");

        // Act
        VersionValue result = parser.parse("2024.04");

        // Assert
        assertInstanceOf(CalverVersion.class, result,
                "A CALVER parser must return a CalverVersion instance");
        assertEquals("2024.04", result.value());
    }

    @Test
    void calverParser_isSingleInstance_sharedByBothLegs() {
        // The same parser instance must be usable multiple times (current + latest leg).
        VersionParser parser = new VersionParser(VersionScheme.CALVER, "YYYY.0M");

        VersionValue current = parser.parse("2024.04");
        VersionValue latest  = parser.parse("2025.01");

        assertEquals("2024.04", current.value());
        assertEquals("2025.01", latest.value());
        assertTrue(current.isOlderThan(latest),
                "Versions from the same calver parser must be mutually comparable");
    }

    @Test
    void calverParser_parsedVersions_supportDiff() {
        VersionParser parser = new VersionParser(VersionScheme.CALVER, "YYYY.0M");

        VersionValue older = parser.parse("2024.04");
        VersionValue newer = parser.parse("2025.01");

        assertEquals(VersionValue.Diff.MAJOR, older.diff(newer));
    }

    @Test
    void calverParser_parsedVersion_supportsWithoutPreRelease() {
        VersionParser parser = new VersionParser(VersionScheme.CALVER, "YYYY.0M.MODIFIER");

        VersionValue versioned = parser.parse("2024.04.alpha");
        VersionValue stripped  = versioned.withoutPreRelease();

        assertEquals("2024.04", stripped.value());
        assertEquals("2024.04.alpha", versioned.value(),
                "withoutPreRelease must leave the original unchanged");
    }

    // -----------------------------------------------------------------------
    // CALVER parser — fail-fast at construction (missing / invalid format)
    // -----------------------------------------------------------------------

    @Test
    void calverParser_throwsAtConstruction_whenNoFormatProvided() {
        // Single-arg constructor for CALVER must fail fast: CALVER requires a format string.
        // This replaces the old slice-01 test that expected construction to succeed and
        // parse() to throw UnsupportedOperationException.
        assertThrows(IllegalArgumentException.class,
                () -> new VersionParser(VersionScheme.CALVER),
                "Constructing a CALVER parser without a format must throw at construction");
    }

    @Test
    void calverParser_throwsAtConstruction_whenFormatIsNull() {
        assertThrows(IllegalArgumentException.class,
                () -> new VersionParser(VersionScheme.CALVER, (String) null),
                "A null calver-format must cause construction to fail fast");
    }

    @Test
    void calverParser_throwsAtConstruction_whenFormatIsBlank() {
        assertThrows(IllegalArgumentException.class,
                () -> new VersionParser(VersionScheme.CALVER, ""),
                "A blank calver-format must cause construction to fail fast");
    }

    @Test
    void calverParser_throwsAtConstruction_whenFormatContainsUnknownToken() {
        assertThrows(IllegalArgumentException.class,
                () -> new VersionParser(VersionScheme.CALVER, "YYYY.BADTOKEN"),
                "An unknown token in calver-format must cause construction to fail fast");
    }

    // -----------------------------------------------------------------------
    // CALVER parser — isolated parse failure (bad source string, not bad config)
    // -----------------------------------------------------------------------

    @Test
    void calverParser_throwsInvalidVersionException_whenVersionDoesNotMatchFormat() {
        // A well-configured CALVER parser (good format) must isolate a bad source string to
        // that app's parse() call — it must NOT crash the application at startup.
        VersionParser parser = new VersionParser(VersionScheme.CALVER, "YYYY.0M");

        assertThrows(InvalidVersionException.class,
                () -> parser.parse("not-a-calver-version"),
                "A version string not matching the calver format must throw InvalidVersionException at parse()");
    }

    @Test
    void calverParser_throwsInvalidVersionException_forNullInput() {
        VersionParser parser = new VersionParser(VersionScheme.CALVER, "YYYY.0M");

        assertThrows(InvalidVersionException.class,
                () -> parser.parse(null),
                "A null raw version string must surface as InvalidVersionException");
    }
}
