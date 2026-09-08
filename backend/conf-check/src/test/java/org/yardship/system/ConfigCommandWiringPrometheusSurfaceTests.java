package org.yardship.system;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yardship.confcheck.command.ConfigCommand;
import org.yardship.confcheck.outcome.ValidationOutcome;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end wiring test proving the {@code config} gate's {@code http-prometheus} surface (ADR-0033,
 * slice 04) actually RUNS for a real, disk-parsed app, rather than reporting "not applicable" —
 * mirroring {@code ConfigCommandWiringHeaderSurfaceTests}'s precedent exactly.
 *
 * <p>No WireMock server here (unlike the header-surface wiring test): the prometheus surface in
 * this slice never fetches a body — it is a structural-only check (non-blank {@code url}/{@code
 * metric}, a well-formed optional {@code regex}) — so the app's {@code current.url} is never
 * actually dialed, and running WITHOUT {@code --offline} is safe. Live extraction against a real
 * Prometheus body is slice 05's separate command.
 *
 * <p>A separate class from {@link ConfigCommandWiringTests} (own fixture) so that pre-existing,
 * unrelated test file stays untouched.
 */
class ConfigCommandWiringPrometheusSurfaceTests {

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

    private Path fixture(String name) throws URISyntaxException {
        return Path.of(getClass().getResource("/config/" + name).toURI());
    }

    /**
     * THE load-bearing assertion for this slice: the prometheus surface must report as having RUN
     * and PASSED ("PROMETHEUS: OK"), never "PROMETHEUS: not applicable" — which is what it would
     * report before {@code AppConfig}/{@code YamlAppConfigReader}/{@code ConfigFileValidation} are
     * taught about {@code http-prometheus}. Checking the exit code alone would not catch that
     * regression, since "not applicable" and "ran and passed" both leave the aggregate exit code at
     * {@code ALL_OK}.
     */
    @Test
    void httpPrometheusApp_prometheusSurfaceRunsAndPasses_notReportedAsNotApplicable() throws URISyntaxException {
        Path configFile = fixture("prometheus-app.yaml");

        int exitCode = new CommandLine(new ConfigCommand()).execute(configFile.toString());

        String output = capturedOut.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("PROMETHEUS: OK"),
                "the prometheus surface must actually RUN and PASS for a real http-prometheus app "
                        + "parsed off disk; output was:\n" + output);
        assertFalse(output.contains("PROMETHEUS: not applicable"),
                "a real http-prometheus app must never report the prometheus surface as not "
                        + "applicable; output was:\n" + output);
        assertEquals(ValidationOutcome.ConfigFileResult.ALL_OK_EXIT_CODE, exitCode);
    }
}
