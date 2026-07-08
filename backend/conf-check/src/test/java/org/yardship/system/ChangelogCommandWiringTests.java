package org.yardship.system;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yardship.confcheck.command.ChangelogCommand;
import org.yardship.confcheck.outcome.ValidationOutcome;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end wiring test: invokes the real {@code changelog} picocli command (pure-function, no
 * body source) and asserts both rendered stdout and process exit code. This is the tip of the
 * pyramid for this slice — {@link org.yardship.unit.validation.ChangelogResolutionValidationTests}
 * already covers the resolution/rejection logic exhaustively, so this only proves the command wires
 * {@code VersionSpec -> ChangelogResolutionValidation -> ReportRenderer} together and that the exit
 * codes from {@link ValidationOutcome} actually reach the process.
 *
 * <p>{@link ChangelogCommand#call()} is fully implemented, so every test here is expected to be
 * GREEN, proving the wiring end-to-end.
 */
class ChangelogCommandWiringTests {

    private final ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();
    private PrintStream originalOut;

    @BeforeEach
    void captureStdout() {
        originalOut = System.out;
        System.setOut(new PrintStream(capturedOut, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreStdout() {
        System.setOut(originalOut);
    }

    @Test
    void ok_semverTemplate_exitsZero_andPrintsResolvedUrl() {
        int exitCode = new CommandLine(new ChangelogCommand()).execute(
                "--template", "https://example.com/{major}.{minor}.{patch}",
                "--version", "1.2.3",
                "--scheme", "semver");

        assertEquals(ValidationOutcome.ChangelogOk.EXIT_CODE, exitCode);
        assertTrue(capturedOut.toString(StandardCharsets.UTF_8).contains("https://example.com/1.2.3"),
                "output must mention the resolved URL");
    }

    @Test
    void ok_tokenFreeTemplate_exitsZero_andPrintsConstantUrl() {
        int exitCode = new CommandLine(new ChangelogCommand()).execute(
                "--template", "https://example.com/CHANGELOG.md",
                "--version", "1.2.3",
                "--scheme", "semver");

        assertEquals(ValidationOutcome.ChangelogOk.EXIT_CODE, exitCode);
        assertTrue(capturedOut.toString(StandardCharsets.UTF_8).contains("https://example.com/CHANGELOG.md"));
    }

    @Test
    void ok_calverTemplate_exitsZero_andPrintsResolvedUrl() {
        int exitCode = new CommandLine(new ChangelogCommand()).execute(
                "--template", "https://example.com/{YY}/{0M}",
                "--version", "23.05",
                "--scheme", "calver",
                "--calver-format", "YY.0M");

        assertEquals(ValidationOutcome.ChangelogOk.EXIT_CODE, exitCode);
        assertTrue(capturedOut.toString(StandardCharsets.UTF_8).contains("https://example.com/23/05"));
    }

    @Test
    void configInvalid_illegalTokenForScheme_exitsWithConfigInvalidCode_namingToken() {
        int exitCode = new CommandLine(new ChangelogCommand()).execute(
                "--template", "https://example.com/{YY}",
                "--version", "1.2.3",
                "--scheme", "semver");

        assertEquals(ValidationOutcome.ConfigInvalid.EXIT_CODE, exitCode);
        assertTrue(capturedOut.toString(StandardCharsets.UTF_8).contains("{YY}"),
                "output must name the offending placeholder");
    }

    @Test
    void configInvalid_unparseableVersion_exitsWithConfigInvalidCode() {
        int exitCode = new CommandLine(new ChangelogCommand()).execute(
                "--template", "https://example.com/{major}",
                "--version", "not-a-semver",
                "--scheme", "semver");

        assertEquals(ValidationOutcome.ConfigInvalid.EXIT_CODE, exitCode);
    }

    @Test
    void configInvalid_calverWithoutCalverFormat_exitsWithConfigInvalidCode() {
        int exitCode = new CommandLine(new ChangelogCommand()).execute(
                "--template", "https://example.com/{YY}",
                "--version", "23.05",
                "--scheme", "calver");

        assertEquals(ValidationOutcome.ConfigInvalid.EXIT_CODE, exitCode);
    }

    @Test
    void missingRequiredOption_isUsageError() {
        int exitCode = new CommandLine(new ChangelogCommand()).execute(
                "--version", "1.2.3",
                "--scheme", "semver");

        assertEquals(CommandLine.ExitCode.USAGE, exitCode, "--template is required");
    }
}
