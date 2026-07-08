package org.yardship.system;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yardship.confcheck.command.CalverCommand;
import org.yardship.confcheck.outcome.ValidationOutcome;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end wiring test: invokes the real {@code calver} picocli command (pure-function, no body
 * source, no {@code --scheme} flag) and asserts both rendered stdout and process exit code. This
 * is the tip of the pyramid for this slice — {@link org.yardship.unit.validation.CalverFormatValidationTests}
 * already covers the resolution/rejection logic exhaustively, so this only proves the command
 * wires {@code CalverFormatValidation -> ReportRenderer} together and that the exit codes from
 * {@link ValidationOutcome} actually reach the process.
 *
 * <p>{@link CalverCommand#call()} currently throws {@link UnsupportedOperationException}, so every
 * test here is RED until the implementer wires it up (mirrors {@code ChangelogCommandWiringTests}'
 * role once {@code ChangelogCommand} was implemented — these tests should turn GREEN with no
 * changes once {@code CalverCommand#call()} is implemented).
 */
class CalverCommandWiringTests {

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
    void ok_validFormatAndVersion_exitsZero_andPrintsTokenValueMapping() {
        int exitCode = new CommandLine(new CalverCommand()).execute(
                "--format", "YY.0M.MICRO",
                "--version", "23.05.5");

        assertEquals(ValidationOutcome.CalverOk.EXIT_CODE, exitCode);
        String output = capturedOut.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("YY=23"), "output must contain YY=23");
        assertTrue(output.contains("0M=05"), "output must contain 0M=05 with zero-padding preserved");
        assertTrue(output.contains("MICRO=5"), "output must contain MICRO=5");
    }

    @Test
    void configInvalid_malformedFormat_exitsWithConfigInvalidCode() {
        int exitCode = new CommandLine(new CalverCommand()).execute(
                "--format", "NOTATOKEN",
                "--version", "23.05");

        assertEquals(ValidationOutcome.ConfigInvalid.EXIT_CODE, exitCode);
    }

    @Test
    void configInvalid_versionDoesNotFitFormat_exitsWithConfigInvalidCode() {
        int exitCode = new CommandLine(new CalverCommand()).execute(
                "--format", "YY.0M",
                "--version", "not-a-calver-version");

        assertEquals(ValidationOutcome.ConfigInvalid.EXIT_CODE, exitCode);
    }

    @Test
    void missingRequiredOption_isUsageError() {
        int exitCode = new CommandLine(new CalverCommand()).execute(
                "--version", "23.05");

        assertEquals(CommandLine.ExitCode.USAGE, exitCode, "--format is required");
    }
}
