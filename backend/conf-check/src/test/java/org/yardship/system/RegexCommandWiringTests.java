package org.yardship.system;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yardship.confcheck.command.RegexCommand;
import org.yardship.confcheck.outcome.ValidationOutcome;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end wiring test: invokes the real {@code regex} picocli command (offline body source,
 * literal flags) and asserts both rendered stdout and process exit code. This is the tip of the
 * pyramid for this slice — {@link org.yardship.unit.validation.RegexExtractionValidationTests}
 * already covers the "largest wins" logic exhaustively, so this only proves the command wires
 * {@code BodySource -> VersionSpec -> RegexExtractionValidation -> ReportRenderer} together and
 * that the exit codes from {@link ValidationOutcome} actually reach the process.
 */
class RegexCommandWiringTests {

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
    void ok_multipleMatches_largestWins_exitsZero_andPrintsWinner(@TempDir Path tempDir) throws IOException {
        Path bodyFile = tempDir.resolve("body.txt");
        Files.writeString(bodyFile, "1.2.0\n2.0.0\n1.9.9", StandardCharsets.UTF_8);

        int exitCode = new CommandLine(new RegexCommand()).execute(
                "--regex", "(\\d+\\.\\d+\\.\\d+)",
                "--scheme", "semver",
                "--body-file", bodyFile.toString());

        assertEquals(ValidationOutcome.Ok.EXIT_CODE, exitCode);
        assertTrue(capturedOut.toString(StandardCharsets.UTF_8).contains("2.0.0"),
                "output must mention the winning version 2.0.0");
    }

    @Test
    void urlRedirect_finalBodyLargestVersion_exitsZeroAndPrintsWinner() {
        WireMockServer wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        try {
            wireMock.stubFor(get(urlPathEqualTo("/redirected-body"))
                    .willReturn(aResponse()
                            .withStatus(301)
                            .withHeader("Location", "/final-body")));
            wireMock.stubFor(get(urlPathEqualTo("/final-body"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withBody("Version: 1.2.0\nVersion: 2.0.0\nVersion: 1.9.9")));

            int exitCode = new CommandLine(new RegexCommand()).execute(
                    "--regex", "(\\d+\\.\\d+\\.\\d+)",
                    "--scheme", "semver",
                    "--url", wireMock.baseUrl() + "/redirected-body");

            assertEquals(ValidationOutcome.Ok.EXIT_CODE, exitCode);
            assertTrue(capturedOut.toString(StandardCharsets.UTF_8).lines()
                            .anyMatch(line -> line.equals("Winner: 2.0.0 -> 2.0.0")),
                    "output must identify 2.0.0 as the winner from the redirected final body");
        } finally {
            wireMock.stop();
        }
    }

    @Test
    void urlRedirectLoop_exitsWithFetchFailedCode() {
        WireMockServer wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        try {
            wireMock.stubFor(get(urlPathEqualTo("/redirect-loop"))
                    .willReturn(aResponse()
                            .withStatus(302)
                            .withHeader("Location", "/redirect-loop")));

            int exitCode = new CommandLine(new RegexCommand()).execute(
                    "--regex", "(\\d+\\.\\d+\\.\\d+)",
                    "--scheme", "semver",
                    "--url", wireMock.baseUrl() + "/redirect-loop");

            assertEquals(ValidationOutcome.FetchFailed.EXIT_CODE, exitCode,
                    "a failed redirect chain must preserve the regex command's FetchFailed exit code");
        } finally {
            wireMock.stop();
        }
    }

    @Test
    void configInvalid_badRegex_exitsWithConfigInvalidCode(@TempDir Path tempDir) throws IOException {
        Path bodyFile = tempDir.resolve("body.txt");
        Files.writeString(bodyFile, "1.2.3", StandardCharsets.UTF_8);

        int exitCode = new CommandLine(new RegexCommand()).execute(
                "--regex", "(unclosed",
                "--scheme", "semver",
                "--body-file", bodyFile.toString());

        assertEquals(ValidationOutcome.ConfigInvalid.EXIT_CODE, exitCode);
    }

    @Test
    void noBodySourceSupplied_isUsageError(@TempDir Path tempDir) {
        int exitCode = new CommandLine(new RegexCommand()).execute(
                "--regex", "(\\d+\\.\\d+\\.\\d+)",
                "--scheme", "semver");

        assertEquals(CommandLine.ExitCode.USAGE, exitCode,
                "zero body sources must be rejected by picocli's ArgGroup as a usage error, before any validation runs");
    }

    @Test
    void twoBodySourcesSupplied_isUsageError(@TempDir Path tempDir) throws IOException {
        Path bodyFile = tempDir.resolve("body.txt");
        Files.writeString(bodyFile, "1.2.3", StandardCharsets.UTF_8);

        int exitCode = new CommandLine(new RegexCommand()).execute(
                "--regex", "(\\d+\\.\\d+\\.\\d+)",
                "--scheme", "semver",
                "--body-file", bodyFile.toString(),
                "--url", "http://localhost:1/unused");

        assertEquals(CommandLine.ExitCode.USAGE, exitCode,
                "supplying both --body-file and --url must be rejected as a usage error");
    }
}
