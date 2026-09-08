package org.yardship.system;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yardship.confcheck.command.MetricCommand;
import org.yardship.confcheck.outcome.ValidationOutcome;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end wiring test: invokes the real {@code metric} picocli command and asserts both
 * rendered stdout and process exit code, mirroring {@link RegexCommandWiringTests}'s shape — this
 * subcommand follows {@code RegexCommand}'s {@code BodySource} shape (file/stdin/live URL), NOT
 * {@code HeaderCommand}'s {@code ResponseSource} shape. Unit tests in
 * {@link org.yardship.unit.validation.MetricExtractionValidationTests} cover the extraction and
 * selection logic; this class verifies the
 * {@code BodySource -> (optional) VersionSpec -> MetricExtractionValidation -> ReportRenderer}
 * command wiring, {@code --label}/{@code --version-label} CLI parsing, and propagation of
 * {@link ValidationOutcome} exit codes.
 *
 * <p>Dedicated WireMock port 8098 — distinct from this module's other WireMock users
 * (8090/8093/8094/8095/8097).
 */
class MetricCommandWiringTests {

    static WireMockServer wireMockServer;

    private final ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();
    private PrintStream originalOut;
    private InputStream originalIn;

    private static final String BLACKBOX_BODY = """
            # HELP blackbox_exporter_build_info A metric with a constant '1' value labeled by version, revision, branch.
            # TYPE blackbox_exporter_build_info gauge
            blackbox_exporter_build_info{branch="HEAD",goversion="go1.22.4",revision="0ec2a6b",version="0.25.0"} 1
            """;

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(options().port(8098));
        wireMockServer.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMockServer.stop();
    }

    @BeforeEach
    void captureIo() {
        originalOut = System.out;
        System.setOut(new PrintStream(capturedOut, true, StandardCharsets.UTF_8));
        originalIn = System.in;
        wireMockServer.resetAll();
    }

    @AfterEach
    void restoreIo() {
        System.setOut(originalOut);
        System.setIn(originalIn);
    }

    private String out() {
        return capturedOut.toString(StandardCharsets.UTF_8);
    }

    // --- Body source selection: --url / --body-file / stdin, exactly one required ------------------

    @Test
    void ok_bodyFile_blackboxShapedFixture_reportsTheVersionLabel_exitsZero(@TempDir Path tempDir) throws IOException {
        Path bodyFile = tempDir.resolve("blackbox.txt");
        Files.writeString(bodyFile, BLACKBOX_BODY, StandardCharsets.UTF_8);

        int exitCode = new CommandLine(new MetricCommand()).execute(
                "--metric", "blackbox_exporter_build_info",
                "--body-file", bodyFile.toString());

        assertEquals(ValidationOutcome.MetricOk.EXIT_CODE, exitCode);
        assertTrue(out().contains("0.25.0"), "output must mention the resolved version 0.25.0");
    }

    @Test
    void ok_stdin_blackboxShapedFixture_reportsTheVersionLabel_exitsZero() {
        System.setIn(new ByteArrayInputStream(BLACKBOX_BODY.getBytes(StandardCharsets.UTF_8)));

        int exitCode = new CommandLine(new MetricCommand()).execute(
                "--metric", "blackbox_exporter_build_info",
                "-");

        assertEquals(ValidationOutcome.MetricOk.EXIT_CODE, exitCode);
        assertTrue(out().contains("0.25.0"));
    }

    @Test
    void ok_url_blackboxShapedFixture_reportsTheVersionLabel_exitsZero() {
        wireMockServer.stubFor(get(urlPathEqualTo("/metrics"))
                .willReturn(aResponse().withStatus(200).withBody(BLACKBOX_BODY)));

        int exitCode = new CommandLine(new MetricCommand()).execute(
                "--metric", "blackbox_exporter_build_info",
                "--url", "http://localhost:8098/metrics");

        assertEquals(ValidationOutcome.MetricOk.EXIT_CODE, exitCode);
        assertTrue(out().contains("0.25.0"));
    }

    @Test
    void noBodySourceSupplied_isUsageError() {
        int exitCode = new CommandLine(new MetricCommand()).execute("--metric", "blackbox_exporter_build_info");

        assertEquals(CommandLine.ExitCode.USAGE, exitCode,
                "zero body sources must be rejected by picocli's ArgGroup as a usage error, before any validation runs");
    }

    @Test
    void twoBodySourcesSupplied_isUsageError(@TempDir Path tempDir) throws IOException {
        Path bodyFile = tempDir.resolve("blackbox.txt");
        Files.writeString(bodyFile, BLACKBOX_BODY, StandardCharsets.UTF_8);

        int exitCode = new CommandLine(new MetricCommand()).execute(
                "--metric", "blackbox_exporter_build_info",
                "--body-file", bodyFile.toString(),
                "--url", "http://localhost:1/unused");

        assertEquals(CommandLine.ExitCode.USAGE, exitCode,
                "supplying both --body-file and --url must be rejected as a usage error");
    }

    @Test
    void missingMetricOption_isUsageError(@TempDir Path tempDir) throws IOException {
        Path bodyFile = tempDir.resolve("blackbox.txt");
        Files.writeString(bodyFile, BLACKBOX_BODY, StandardCharsets.UTF_8);

        int exitCode = new CommandLine(new MetricCommand()).execute("--body-file", bodyFile.toString());

        assertEquals(CommandLine.ExitCode.USAGE, exitCode, "--metric is required");
    }

    @Test
    void connectionError_isFetchFailed() {
        int exitCode = new CommandLine(new MetricCommand()).execute(
                "--metric", "blackbox_exporter_build_info",
                "--url", "http://localhost:8199/unreachable");

        assertEquals(ValidationOutcome.FetchFailed.EXIT_CODE, exitCode,
                "a genuine transport failure (connection refused) must exit with FetchFailed");
    }

    // --- --version-label: honoured, defaults to "version" --------------------------------------------

    @Test
    void versionLabel_default_isVersion(@TempDir Path tempDir) throws IOException {
        Path bodyFile = tempDir.resolve("body.txt");
        Files.writeString(bodyFile, BLACKBOX_BODY, StandardCharsets.UTF_8);

        int exitCode = new CommandLine(new MetricCommand()).execute(
                "--metric", "blackbox_exporter_build_info",
                "--body-file", bodyFile.toString());

        assertEquals(ValidationOutcome.MetricOk.EXIT_CODE, exitCode);
        assertTrue(out().contains("0.25.0"));
    }

    @Test
    void versionLabel_custom_isHonoured(@TempDir Path tempDir) throws IOException {
        Path bodyFile = tempDir.resolve("body.txt");
        Files.writeString(bodyFile, "some_metric{app_version=\"3.4.5\"} 1\n", StandardCharsets.UTF_8);

        int exitCode = new CommandLine(new MetricCommand()).execute(
                "--metric", "some_metric",
                "--version-label", "app_version",
                "--body-file", bodyFile.toString());

        assertEquals(ValidationOutcome.MetricOk.EXIT_CODE, exitCode);
        assertTrue(out().contains("3.4.5"));
    }

    // --- --label: repeatable, ANDs, exact match -------------------------------------------------------

    @Test
    void label_repeatable_ANDsEntries_selectsTheCorrectInstallation(@TempDir Path tempDir) throws IOException {
        String body = """
                blackbox_exporter_build_info{job="blackbox",pod_name="blackbox-0",version="1.0.0"} 1
                blackbox_exporter_build_info{job="blackbox",pod_name="blackbox-1",version="2.0.0"} 1
                """;
        Path bodyFile = tempDir.resolve("body.txt");
        Files.writeString(bodyFile, body, StandardCharsets.UTF_8);

        int exitCode = new CommandLine(new MetricCommand()).execute(
                "--metric", "blackbox_exporter_build_info",
                "--label", "job=blackbox",
                "--label", "pod_name=blackbox-1",
                "--body-file", bodyFile.toString());

        assertEquals(ValidationOutcome.MetricOk.EXIT_CODE, exitCode);
        assertTrue(out().contains("2.0.0"));
        assertFalse(out().contains("1.0.0"), "the sample not matching every --label entry must not be reported as the taken value");
    }

    // --- Selector matched nothing: distinct from an absent metric --------------------------------------

    @Test
    void selectorMatchedNothing_exitsValidButEmpty_distinctFromMetricAbsent(@TempDir Path tempDir) throws IOException {
        Path bodyFile = tempDir.resolve("body.txt");
        Files.writeString(bodyFile, BLACKBOX_BODY, StandardCharsets.UTF_8);

        int selectorExitCode = new CommandLine(new MetricCommand()).execute(
                "--metric", "blackbox_exporter_build_info",
                "--label", "job=does-not-exist",
                "--body-file", bodyFile.toString());
        String selectorOutput = out();
        capturedOut.reset();

        int absentExitCode = new CommandLine(new MetricCommand()).execute(
                "--metric", "no_such_metric",
                "--body-file", bodyFile.toString());
        String absentOutput = out();

        assertEquals(ValidationOutcome.MetricValidButEmpty.EXIT_CODE, selectorExitCode);
        assertEquals(ValidationOutcome.MetricValidButEmpty.EXIT_CODE, absentExitCode);
        assertFalse(selectorOutput.equals(absentOutput),
                "a selector matching nothing must be reported distinctly from an absent metric");
    }

    // --- Several matching samples: reported as such, naming the one taken --------------------------

    @Test
    void severalMatchingSamples_reportsAmbiguity_exitsZero(@TempDir Path tempDir) throws IOException {
        String body = """
                blackbox_exporter_build_info{instance="a",version="0.24.0"} 1
                blackbox_exporter_build_info{instance="b",version="0.25.0"} 1
                """;
        Path bodyFile = tempDir.resolve("body.txt");
        Files.writeString(bodyFile, body, StandardCharsets.UTF_8);

        int exitCode = new CommandLine(new MetricCommand()).execute(
                "--metric", "blackbox_exporter_build_info",
                "--body-file", bodyFile.toString());

        assertEquals(ValidationOutcome.MetricOk.EXIT_CODE, exitCode);
        assertTrue(out().contains("0.24.0"), "the FIRST sample in document order must be the one reported as taken");
        assertTrue(out().contains("2") || out().toLowerCase().contains("matched"),
                "the report must note that more than one sample matched");
    }

    // --- --regex: first-match group-1 extraction; non-compiling pattern is reported, not thrown -------

    @Test
    void regex_firstMatchGroup1_exitsZero(@TempDir Path tempDir) throws IOException {
        Path bodyFile = tempDir.resolve("body.txt");
        Files.writeString(bodyFile, "my_metric{version=\"v1.2.3-final\"} 1\n", StandardCharsets.UTF_8);

        int exitCode = new CommandLine(new MetricCommand()).execute(
                "--metric", "my_metric",
                "--regex", "v(\\d+\\.\\d+\\.\\d+)",
                "--scheme", "semver",
                "--body-file", bodyFile.toString());

        assertEquals(ValidationOutcome.MetricOk.EXIT_CODE, exitCode);
        assertTrue(out().contains("1.2.3"));
    }

    @Test
    void regex_nonCompilingPattern_isReported_notThrownAsAStackTrace(@TempDir Path tempDir) throws IOException {
        Path bodyFile = tempDir.resolve("body.txt");
        Files.writeString(bodyFile, BLACKBOX_BODY, StandardCharsets.UTF_8);

        int exitCode = new CommandLine(new MetricCommand()).execute(
                "--metric", "blackbox_exporter_build_info",
                "--regex", "(unclosed",
                "--scheme", "semver",
                "--body-file", bodyFile.toString());

        assertEquals(ValidationOutcome.ConfigInvalid.EXIT_CODE, exitCode);
        assertFalse(out().contains("Exception"), "a non-compiling regex must be reported, never surfaced as a raw stack trace");
    }

    // --- --scheme optional; --scheme calver without --calver-format is rejected -----------------------

    @Test
    void schemeOmitted_checksResolutionOnly_exitsZeroEvenForANonSemverValue(@TempDir Path tempDir) throws IOException {
        Path bodyFile = tempDir.resolve("body.txt");
        Files.writeString(bodyFile, "my_metric{version=\"not-a-version\"} 1\n", StandardCharsets.UTF_8);

        int exitCode = new CommandLine(new MetricCommand()).execute(
                "--metric", "my_metric",
                "--body-file", bodyFile.toString());

        assertEquals(ValidationOutcome.MetricOk.EXIT_CODE, exitCode,
                "omitting --scheme must check resolution only, regardless of whether the value would parse");
        assertTrue(out().contains("not-a-version"));
    }

    @Test
    void schemeCalver_withoutCalverFormat_isRejected_asTheSiblingCommandsReject(@TempDir Path tempDir) throws IOException {
        Path bodyFile = tempDir.resolve("body.txt");
        Files.writeString(bodyFile, BLACKBOX_BODY, StandardCharsets.UTF_8);

        int exitCode = new CommandLine(new MetricCommand()).execute(
                "--metric", "blackbox_exporter_build_info",
                "--scheme", "calver",
                "--body-file", bodyFile.toString());

        assertEquals(ValidationOutcome.ConfigInvalid.EXIT_CODE, exitCode);
    }

    // --- --strip-prerelease matches the backend's semantics ------------------------------------------

    @Test
    void stripPrerelease_matchesBackendSemantics_exitsZero(@TempDir Path tempDir) throws IOException {
        Path bodyFile = tempDir.resolve("body.txt");
        Files.writeString(bodyFile, "my_metric{version=\"1.2.3-rc1\"} 1\n", StandardCharsets.UTF_8);

        int exitCode = new CommandLine(new MetricCommand()).execute(
                "--metric", "my_metric",
                "--scheme", "semver",
                "--strip-prerelease",
                "--body-file", bodyFile.toString());

        assertEquals(ValidationOutcome.MetricOk.EXIT_CODE, exitCode);
        assertTrue(out().contains("1.2.3"));
        assertFalse(out().contains("1.2.3-rc1") && out().contains("Parsed: 1.2.3-rc1"),
                "the parsed/reported version must have its pre-release segment stripped");
    }

    @Test
    void stripPrerelease_withoutScheme_isConfigInvalid(@TempDir Path tempDir) throws IOException {
        Path bodyFile = tempDir.resolve("body.txt");
        Files.writeString(bodyFile, BLACKBOX_BODY, StandardCharsets.UTF_8);

        int exitCode = new CommandLine(new MetricCommand()).execute(
                "--metric", "blackbox_exporter_build_info",
                "--strip-prerelease",
                "--body-file", bodyFile.toString());

        assertEquals(ValidationOutcome.ConfigInvalid.EXIT_CODE, exitCode, "--strip-prerelease requires --scheme");
    }

    // --- version-label absent / metric absent exit codes, matching the ValidButEmpty convention ------

    @Test
    void metricAbsent_exitsValidButEmpty(@TempDir Path tempDir) throws IOException {
        Path bodyFile = tempDir.resolve("body.txt");
        Files.writeString(bodyFile, BLACKBOX_BODY, StandardCharsets.UTF_8);

        int exitCode = new CommandLine(new MetricCommand()).execute(
                "--metric", "no_such_metric",
                "--body-file", bodyFile.toString());

        assertEquals(ValidationOutcome.MetricValidButEmpty.EXIT_CODE, exitCode);
    }

    @Test
    void versionLabelAbsentFromMatchedSample_exitsValidButEmpty(@TempDir Path tempDir) throws IOException {
        Path bodyFile = tempDir.resolve("body.txt");
        Files.writeString(bodyFile, "blackbox_exporter_build_info{job=\"blackbox\"} 1\n", StandardCharsets.UTF_8);

        int exitCode = new CommandLine(new MetricCommand()).execute(
                "--metric", "blackbox_exporter_build_info",
                "--body-file", bodyFile.toString());

        assertEquals(ValidationOutcome.MetricValidButEmpty.EXIT_CODE, exitCode);
    }
}
