package org.yardship.system;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yardship.confcheck.command.PointerCommand;
import org.yardship.confcheck.outcome.ValidationOutcome;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end wiring test: invokes the real {@code pointer} picocli command (offline body source,
 * literal flags) and asserts both rendered stdout and process exit code. This is the tip of the
 * pyramid for this slice — {@link org.yardship.unit.validation.PointerExtractionValidationTests}
 * already covers the extraction/parse logic exhaustively, so this only proves the command wires
 * {@code BodySource -> (optional) VersionSpec -> PointerExtractionValidation -> ReportRenderer}
 * together and that the exit codes from {@link ValidationOutcome} actually reach the process.
 *
 * <p>{@link PointerCommand#call()} is currently a stub (throws {@link UnsupportedOperationException}),
 * so every test here is expected to be RED until the implementer fills in the wiring — picocli's
 * default execution exception handling turns that thrown exception into a non-zero, non-matching
 * exit code, so these assertions fail loudly rather than silently.
 */
class PointerCommandWiringTests {

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
    void ok_pointerFound_noScheme_exitsZero_andPrintsExtractedValue(@TempDir Path tempDir) throws IOException {
        Path bodyFile = tempDir.resolve("body.json");
        Files.writeString(bodyFile, "{\"version\":\"1.2.3\"}", StandardCharsets.UTF_8);

        int exitCode = new CommandLine(new PointerCommand()).execute(
                "--key", "/version",
                "--body-file", bodyFile.toString());

        assertEquals(ValidationOutcome.PointerOk.EXIT_CODE, exitCode);
        assertTrue(capturedOut.toString(StandardCharsets.UTF_8).contains("1.2.3"),
                "output must mention the extracted value 1.2.3");
    }

    @Test
    void ok_pointerFound_withScheme_exitsZero_andPrintsParsedVersion(@TempDir Path tempDir) throws IOException {
        Path bodyFile = tempDir.resolve("body.json");
        Files.writeString(bodyFile, "{\"version\":\"1.2.3\"}", StandardCharsets.UTF_8);

        int exitCode = new CommandLine(new PointerCommand()).execute(
                "--key", "/version",
                "--scheme", "semver",
                "--body-file", bodyFile.toString());

        assertEquals(ValidationOutcome.PointerOk.EXIT_CODE, exitCode);
        assertTrue(capturedOut.toString(StandardCharsets.UTF_8).contains("1.2.3"));
    }

    @Test
    void validButEmpty_pointerAbsent_exitsWithValidButEmptyCode(@TempDir Path tempDir) throws IOException {
        Path bodyFile = tempDir.resolve("body.json");
        Files.writeString(bodyFile, "{\"other\":\"1.2.3\"}", StandardCharsets.UTF_8);

        int exitCode = new CommandLine(new PointerCommand()).execute(
                "--key", "/version",
                "--body-file", bodyFile.toString());

        assertEquals(ValidationOutcome.PointerValidButEmpty.EXIT_CODE, exitCode);
    }

    @Test
    void noBodySourceSupplied_isUsageError() {
        int exitCode = new CommandLine(new PointerCommand()).execute("--key", "/version");

        assertEquals(CommandLine.ExitCode.USAGE, exitCode,
                "zero body sources must be rejected by picocli's ArgGroup as a usage error, before any validation runs");
    }

    @Test
    void twoBodySourcesSupplied_isUsageError(@TempDir Path tempDir) throws IOException {
        Path bodyFile = tempDir.resolve("body.json");
        Files.writeString(bodyFile, "{\"version\":\"1.2.3\"}", StandardCharsets.UTF_8);

        int exitCode = new CommandLine(new PointerCommand()).execute(
                "--key", "/version",
                "--body-file", bodyFile.toString(),
                "--url", "http://localhost:1/unused");

        assertEquals(CommandLine.ExitCode.USAGE, exitCode,
                "supplying both --body-file and --url must be rejected as a usage error");
    }

    @Test
    void missingKeyOption_isUsageError(@TempDir Path tempDir) throws IOException {
        Path bodyFile = tempDir.resolve("body.json");
        Files.writeString(bodyFile, "{\"version\":\"1.2.3\"}", StandardCharsets.UTF_8);

        int exitCode = new CommandLine(new PointerCommand()).execute(
                "--body-file", bodyFile.toString());

        assertEquals(CommandLine.ExitCode.USAGE, exitCode, "--key is required");
    }

    /**
     * There is no well-defined "pre-release" concept for a bare, unparsed string —
     * {@code VersionValue.withoutPreRelease()} only exists on a parsed value — so
     * {@code --scheme} must be required whenever {@code --strip-prerelease} is set. This is
     * enforced as an application-level check in {@link PointerCommand#call()}, rendered as
     * {@link ValidationOutcome.ConfigInvalid} (exit code 2) — the same pattern {@code call()}
     * already uses for other config problems (e.g. an invalid {@code --scheme} value or a bad
     * {@code --calver-format}), which is caught and rendered via
     * {@code ValidationOutcome.ConfigInvalid} before any body source is touched. This was
     * preferred over a picocli-declarative option-interdependency (e.g. restructuring
     * {@code --scheme} into an {@code ArgGroup}) because picocli has no built-in "option A
     * requires option B" annotation for two independently-optional {@code @Option} fields, and
     * the codebase already has an established, simpler idiom for exactly this kind of
     * config-level rejection.
     */
    @Test
    void stripPrerelease_withoutScheme_isConfigInvalid(@TempDir Path tempDir) throws IOException {
        Path bodyFile = tempDir.resolve("body.json");
        Files.writeString(bodyFile, "{\"version\":\"1.2.3-rc.1\"}", StandardCharsets.UTF_8);

        int exitCode = new CommandLine(new PointerCommand()).execute(
                "--key", "/version",
                "--strip-prerelease",
                "--body-file", bodyFile.toString());

        assertEquals(ValidationOutcome.ConfigInvalid.EXIT_CODE, exitCode,
                "--strip-prerelease without --scheme has no well-defined pre-release concept "
                        + "(VersionValue.withoutPreRelease() requires a parsed value) and must be "
                        + "rejected as a config error, not silently accepted");
    }
}
