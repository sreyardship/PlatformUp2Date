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
 * <p>This suite owns three concerns only:
 * <ol>
 *   <li><b>Type-routing</b> — SEMVER {@code parse()} returns {@link SemverVersion}; CALVER
 *       {@code parse()} returns {@link CalverVersion}.</li>
 *   <li><b>Fail-fast construction</b> — invalid or missing CALVER format throws
 *       {@link IllegalArgumentException} at construction time, not at parse time.</li>
 *   <li><b>Parse-time error surfacing</b> — a single smoke proving that a bad version string
 *       surfaces as {@link InvalidVersionException} from {@code parse()}.</li>
 * </ol>
 *
 * <p>Value-object behaviour (v-prefix stripping, diff severity, withoutPreRelease content,
 * ordering, and the full malformed-input matrix) is owned by {@code SemverVersionTests} and
 * {@code CalverVersionTests} respectively and is not duplicated here.
 *
 * <p>Assumed API surface for the implementer:
 * <ul>
 *   <li>{@code VersionParser(VersionScheme scheme)} — single-arg constructor; works for SEMVER;
 *       throws {@link IllegalArgumentException} at construction for CALVER (no format supplied).</li>
 *   <li>{@code VersionParser(VersionScheme scheme, String calverFormat)} — two-arg constructor for
 *       CALVER; throws {@link IllegalArgumentException} at construction when {@code calverFormat} is
 *       null, blank, or contains an unknown token.</li>
 *   <li>{@code VersionValue parse(String raw)} — SEMVER returns {@link SemverVersion}; CALVER
 *       returns {@link CalverVersion}; throws {@link InvalidVersionException} when {@code raw} does
 *       not match the format.</li>
 * </ul>
 *
 * <p>This is a pure domain unit test — no Quarkus context needed.
 */
public class VersionParserTests {

    // -----------------------------------------------------------------------
    // Type-routing
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

    // -----------------------------------------------------------------------
    // CALVER parser — fail-fast at construction (missing / invalid format)
    // -----------------------------------------------------------------------

    @Test
    void calverParser_throwsAtConstruction_whenNoFormatProvided() {
        // Single-arg constructor for CALVER must fail fast: CALVER requires a format string.
        assertThrows(IllegalArgumentException.class,
                () -> new VersionParser(VersionScheme.CALVER),
                "Constructing a CALVER parser without a format must throw at construction");
    }

    @Test
    void calverParser_throwsAtConstruction_whenTwoArgFormatIsRejectedByCalverFormat() {
        // Single delegation smoke: the two-arg constructor forwards the format to CalverFormat and
        // propagates its IllegalArgumentException. The authoritative null/blank/unknown-token matrix
        // is owned by CalverFormatTests — not duplicated here.
        assertThrows(IllegalArgumentException.class,
                () -> new VersionParser(VersionScheme.CALVER, "YYYY.BADTOKEN"),
                "A calver-format rejected by CalverFormat must cause construction to fail fast");
    }

    // -----------------------------------------------------------------------
    // Parse-time error surfacing (single smoke — full matrix owned by CalverVersionTests)
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
}
