package org.yardship.system;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yardship.confcheck.command.HeaderCommand;
import org.yardship.confcheck.outcome.ValidationOutcome;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end wiring test: invokes the real {@code header} picocli command and asserts both
 * rendered stdout and process exit code, mirroring {@code PointerCommandWiringTests}'s shape (this
 * subcommand also has an OPTIONAL {@code --scheme}, same as {@code pointer}). Unit tests in
 * {@link org.yardship.unit.validation.HeaderExtractionValidationTests} cover the extraction and
 * parsing logic; this class verifies the
 * {@code ResponseSource -> (optional) VersionSpec -> HeaderExtractionValidation -> ReportRenderer}
 * command wiring and propagation of {@link ValidationOutcome} exit codes.
 *
 * <p>Most cases here run entirely offline (fixture status/headers via {@code --status}/
 * {@code --header-value}), mirroring how {@code pointer}'s wiring tests run against
 * {@code --body-file}. One test drives the real {@code --url} path against WireMock (port 8095 —
 * distinct from this module's other WireMock users: 8090/8093/8094), proving the command is wired
 * to the real {@link org.yardship.confcheck.adapter.LiveHttpResponseSource}, including that a
 * non-2xx response is used rather than rejected.
 */
class HeaderCommandWiringTests {

    static WireMockServer wireMockServer;

    private final ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();
    private PrintStream originalOut;

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(options().port(8095));
        wireMockServer.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMockServer.stop();
    }

    @BeforeEach
    void captureStdout() {
        originalOut = System.out;
        System.setOut(new PrintStream(capturedOut, true, StandardCharsets.UTF_8));
        wireMockServer.resetAll();
    }

    @AfterEach
    void restoreStdout() {
        System.setOut(originalOut);
    }

    @Test
    void ok_headerFound_noScheme_exitsZero_andPrintsExtractedValue() {
        int exitCode = new CommandLine(new HeaderCommand()).execute(
                "--header", "X-Jenkins",
                "--offline",
                "--header-value", "X-Jenkins=2.568.2");

        assertEquals(ValidationOutcome.HeaderOk.EXIT_CODE, exitCode);
        assertTrue(capturedOut.toString(StandardCharsets.UTF_8).contains("2.568.2"),
                "output must mention the extracted value 2.568.2");
    }

    @Test
    void ok_headerFound_withScheme_exitsZero_andPrintsParsedVersion() {
        int exitCode = new CommandLine(new HeaderCommand()).execute(
                "--header", "X-Jenkins",
                "--scheme", "semver",
                "--offline",
                "--header-value", "X-Jenkins=2.568.2");

        assertEquals(ValidationOutcome.HeaderOk.EXIT_CODE, exitCode);
        assertTrue(capturedOut.toString(StandardCharsets.UTF_8).contains("2.568.2"));
    }

    /**
     * The reported outcome must show the status code the header was read from — the whole point of
     * this slice — visible on a PASSING outcome too (a 403 with a resolvable header is a pass).
     */
    @Test
    void ok_nonTwoXxFixtureStatus_stillExitsZero_andPrintsTheObservedStatus() {
        int exitCode = new CommandLine(new HeaderCommand()).execute(
                "--header", "X-Jenkins",
                "--scheme", "semver",
                "--offline",
                "--status", "403",
                "--header-value", "X-Jenkins=2.568.2");

        assertEquals(ValidationOutcome.HeaderOk.EXIT_CODE, exitCode,
                "a non-2xx status must not fail header validation — the header still resolved");
        assertTrue(capturedOut.toString(StandardCharsets.UTF_8).contains("403"),
                "output must show the status code (403) the header was read from, so an operator "
                        + "sees this is working as designed, not a fluke");
    }

    @Test
    void validButEmpty_headerAbsent_exitsWithValidButEmptyCode_andPrintsTheObservedStatus() {
        int exitCode = new CommandLine(new HeaderCommand()).execute(
                "--header", "X-Jenkins",
                "--offline",
                "--status", "403");

        assertEquals(ValidationOutcome.HeaderValidButEmpty.EXIT_CODE, exitCode);
        assertTrue(capturedOut.toString(StandardCharsets.UTF_8).contains("403"),
                "an absent-header failure must still show the observed status");
    }

    @Test
    void withRegex_firstMatchWins_exitsZero() {
        int exitCode = new CommandLine(new HeaderCommand()).execute(
                "--header", "X-Jenkins",
                "--regex", "(\\d+\\.\\d+\\.\\d+)",
                "--scheme", "semver",
                "--offline",
                "--header-value", "X-Jenkins=1.0.0 then later 9.9.9");

        assertEquals(ValidationOutcome.HeaderOk.EXIT_CODE, exitCode);
        assertTrue(capturedOut.toString(StandardCharsets.UTF_8).contains("1.0.0"));
        assertTrue(!capturedOut.toString(StandardCharsets.UTF_8).contains("9.9.9"),
                "the FIRST match (1.0.0) must win, never the largest (9.9.9)");
    }

    @Test
    void missingHeaderOption_isUsageError() {
        int exitCode = new CommandLine(new HeaderCommand()).execute(
                "--offline", "--header-value", "X-Jenkins=2.568.2");

        assertEquals(CommandLine.ExitCode.USAGE, exitCode, "--header is required");
    }

    @Test
    void noResponseSourceSupplied_isUsageError() {
        int exitCode = new CommandLine(new HeaderCommand()).execute("--header", "X-Jenkins");

        assertEquals(CommandLine.ExitCode.USAGE, exitCode,
                "zero response sources must be rejected as a usage error, before any validation runs");
    }

    @Test
    void bothUrlAndOfflineSupplied_isUsageError() {
        int exitCode = new CommandLine(new HeaderCommand()).execute(
                "--header", "X-Jenkins",
                "--url", "http://localhost:1/unused",
                "--offline");

        assertEquals(CommandLine.ExitCode.USAGE, exitCode,
                "supplying both --url and --offline must be rejected as a usage error");
    }

    // Verify --url really uses LiveHttpResponseSource (real HTTP, real redirect handling), and that
    // a non-2xx final response over the wire is used rather than rejected.

    @Test
    void ok_urlToASecuredEndpoint_403Response_stillResolvesTheHeader() {
        wireMockServer.stubFor(get(urlPathEqualTo("/"))
                .willReturn(aResponse().withStatus(403).withHeader("X-Jenkins", "2.568.2")));

        int exitCode = new CommandLine(new HeaderCommand()).execute(
                "--header", "X-Jenkins",
                "--scheme", "semver",
                "--url", "http://localhost:8095/");

        assertEquals(ValidationOutcome.HeaderOk.EXIT_CODE, exitCode,
                "the real header command wired to --url must resolve a header off a 403 response, "
                        + "proving the CLI composition root uses LiveHttpResponseSource (which returns "
                        + "non-2xx responses) and not LiveHttpBodySource (which would throw)");
        assertTrue(capturedOut.toString(StandardCharsets.UTF_8).contains("2.568.2"));
    }

    @Test
    void connectionError_isFetchFailed() {
        int exitCode = new CommandLine(new HeaderCommand()).execute(
                "--header", "X-Jenkins",
                "--url", "http://localhost:8096/unreachable");

        assertEquals(ValidationOutcome.FetchFailed.EXIT_CODE, exitCode,
                "a genuine transport failure (connection refused) must exit with FetchFailed");
    }
}
