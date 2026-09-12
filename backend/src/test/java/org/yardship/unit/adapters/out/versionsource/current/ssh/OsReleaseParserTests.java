package org.yardship.unit.adapters.out.versionsource.current.ssh;

import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.current.ssh.OsReleaseParser;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link OsReleaseParser} — owns the full os-release parsing matrix.
 *
 * <p>No SSH, no network, no fixtures. Every test is a pure-function call over a raw string.
 *
 * <h2>Coverage</h2>
 * <ul>
 *   <li>Double-quoted value → quotes stripped</li>
 *   <li>Single-quoted value → quotes stripped</li>
 *   <li>Unquoted value → returned as-is</li>
 *   <li>Default field {@code VERSION_ID} extracted from Ubuntu 24.04 os-release text</li>
 *   <li>Default field {@code VERSION_ID} extracted from OpenWRT 23.05.5 os-release text</li>
 *   <li>Default field {@code VERSION_ID} extracted from a semver VM os-release text</li>
 *   <li>Custom field {@code BUILD_ID} extracted when {@code VERSION_ID} is absent</li>
 *   <li>Missing field throws {@link IllegalStateException}</li>
 * </ul>
 */
class OsReleaseParserTests {

    private final OsReleaseParser parser = new OsReleaseParser();

    // -----------------------------------------------------------------------
    // Quote stripping
    // -----------------------------------------------------------------------

    @Test
    void doubleQuotedValue_stripsQuotes() {
        String text = "VERSION_ID=\"24.04\"\n";

        String result = parser.extractField(text, "VERSION_ID");

        assertEquals("24.04", result,
                "surrounding double-quotes must be stripped from the field value");
    }

    @Test
    void singleQuotedValue_stripsQuotes() {
        String text = "VERSION_ID='23.05.5'\n";

        String result = parser.extractField(text, "VERSION_ID");

        assertEquals("23.05.5", result,
                "surrounding single-quotes must be stripped from the field value");
    }

    @Test
    void unquotedValue_returnedAsIs() {
        String text = "VERSION_ID=1.0.0\n";

        String result = parser.extractField(text, "VERSION_ID");

        assertEquals("1.0.0", result,
                "an unquoted field value must be returned without modification");
    }

    // -----------------------------------------------------------------------
    // OS-release content variants — VERSION_ID field
    // -----------------------------------------------------------------------

    @Test
    void ubuntuOsRelease_extractsVersionId_24_04() {
        // Ubuntu 24.04: VERSION_ID is double-quoted
        String text = """
                NAME="Ubuntu"
                VERSION="24.04.2 LTS (Noble Numbat)"
                ID=ubuntu
                ID_LIKE=debian
                VERSION_ID="24.04"
                PRETTY_NAME="Ubuntu 24.04.2 LTS"
                """;

        String result = parser.extractField(text, "VERSION_ID");

        assertEquals("24.04", result,
                "must extract VERSION_ID=\"24.04\" from Ubuntu os-release text");
    }

    @Test
    void openWrtOsRelease_extractsVersionId_23_05_5() {
        // OpenWRT 23.05.5: VERSION_ID is double-quoted
        String text = """
                NAME="OpenWrt"
                VERSION="23.05.5"
                ID="openwrt"
                ID_LIKE="lede openwrt"
                VERSION_ID="23.05.5"
                PRETTY_NAME="OpenWrt 23.05.5"
                """;

        String result = parser.extractField(text, "VERSION_ID");

        assertEquals("23.05.5", result,
                "must extract VERSION_ID=\"23.05.5\" from OpenWRT os-release text");
    }

    @Test
    void semverVmOsRelease_extractsVersionId_1_0_0() {
        // A hypothetical semver VM
        String text = """
                NAME="MyApp"
                ID=myapp
                VERSION_ID="1.0.0"
                """;

        String result = parser.extractField(text, "VERSION_ID");

        assertEquals("1.0.0", result,
                "must extract VERSION_ID=\"1.0.0\" from a semver VM os-release text");
    }

    // -----------------------------------------------------------------------
    // Custom field
    // -----------------------------------------------------------------------

    @Test
    void customField_buildId_extractedWhenVersionIdAbsent() {
        // A fixture with no VERSION_ID but a custom BUILD_ID field
        String text = """
                NAME="CustomOS"
                ID=custom
                BUILD_ID="1.2.3"
                """;

        String result = parser.extractField(text, "BUILD_ID");

        assertEquals("1.2.3", result,
                "must extract BUILD_ID instead of VERSION_ID when a custom field is specified");
    }

    // -----------------------------------------------------------------------
    // Missing field
    // -----------------------------------------------------------------------

    @Test
    void missingField_throwsIllegalStateException() {
        // This fixture has no VERSION_ID — the default release field
        String text = """
                NAME="CustomOS"
                ID=custom
                BUILD_ID="1.2.3"
                """;

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> parser.extractField(text, "VERSION_ID"),
                "a field that is not present in the os-release text must throw IllegalStateException");
        assertTrue(ex.getMessage().contains("VERSION_ID"),
                "the error message must mention the missing field; was: " + ex.getMessage());
    }
}
