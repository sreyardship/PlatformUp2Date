package org.yardship.system;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end wiring test proving the {@code config} gate's header surface actually RUNS for a real,
 * disk-parsed {@code http-header} app, rather than reporting "not applicable" — the exact gap the
 * slice-04 review found: {@link ConfigCommandWiringTests} only drives {@code mixed-apps.yaml}
 * (no {@code http-header} app in it), and {@code ConfigFileValidationHeaderSurfaceTests} builds its
 * {@code AppConfig} by hand rather than through {@code YamlAppConfigReader}, so neither test could
 * catch a reader that silently drops {@code current.version-header}/{@code current.regex}.
 *
 * <p>A separate class from {@link ConfigCommandWiringTests} (own fixture, own WireMock server) so
 * that pre-existing, unrelated test file stays untouched, per this issue's "no existing conf-check
 * test is modified" constraint.
 *
 * <p>Runs the real {@code config} command WITHOUT {@code --offline} (unlike
 * {@link ConfigCommandWiringTests}, which only exercises the offline path) against a WireMock
 * server standing in for the fixture's {@code http://localhost:8097/}, so this is a genuine,
 * end-to-end proof that {@code YamlAppConfigReader -> ConfigFileValidation -> LiveHttpResponseSource}
 * resolves a real header. Uses port 8097 — distinct from this module's other WireMock users
 * (8090/8093/8094/8095).
 */
class ConfigCommandWiringHeaderSurfaceTests {

    static WireMockServer wireMockServer;

    private final ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();
    private PrintStream originalOut;

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(options().port(8097));
        wireMockServer.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMockServer.stop();
    }

    @BeforeEach
    void captureStdoutAndResetStubs() {
        originalOut = System.out;
        System.setOut(new PrintStream(capturedOut, true, StandardCharsets.UTF_8));
        wireMockServer.resetAll();
    }

    @AfterEach
    void restoreStdout() {
        System.setOut(originalOut);
    }

    private Path fixture(String name) throws URISyntaxException {
        return Path.of(getClass().getResource("/config/" + name).toURI());
    }

    /**
     * THE load-bearing assertion for this slice's fixed defect: the header surface must report as
     * having RUN and PASSED ("HEADER: OK"), never "HEADER: not applicable" — which is exactly what
     * it reports today, because {@code YamlAppConfigReader} never populates
     * {@code AppConfig#currentHeaderName()}/{@code #currentHeaderRegex()}, so
     * {@code ConfigFileValidation}'s applicability check ({@code app.currentHeaderName().isPresent()})
     * is always false for a real, disk-parsed app. This assertion is expected to be RED until that
     * reader gap is fixed; checking the exit code alone would not catch the regression, since
     * "not applicable" and "ran and passed" both leave the aggregate exit code at ALL_OK.
     */
    @Test
    void httpHeaderApp_headerSurfaceRunsAndPasses_notReportedAsNotApplicable() throws URISyntaxException {
        wireMockServer.stubFor(get(urlPathEqualTo("/"))
                .willReturn(aResponse().withStatus(200).withHeader("X-Jenkins", "2.568.2")));

        Path configFile = fixture("header-app.yaml");

        int exitCode = new CommandLine(new ConfigCommand()).execute(configFile.toString());

        String output = capturedOut.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("HEADER: OK"),
                "the header surface must actually RUN and PASS for a real http-header app parsed "
                        + "off disk; output was:\n" + output);
        assertFalse(output.contains("HEADER: not applicable"),
                "a real http-header app must never report the header surface as not applicable — "
                        + "that is precisely the silent-defect state this test pins against; output was:\n"
                        + output);
        assertEquals(ValidationOutcome.ConfigFileResult.ALL_OK_EXIT_CODE, exitCode);
    }

    /**
     * Same defect, exercised through the ADR-0030 motivating case: a real http-header app's header
     * must still resolve (and the surface still report RAN/OK) off a non-2xx response.
     */
    @Test
    void httpHeaderApp_on403Response_headerSurfaceStillRunsAndPasses() throws URISyntaxException {
        wireMockServer.stubFor(get(urlPathEqualTo("/"))
                .willReturn(aResponse().withStatus(403).withHeader("X-Jenkins", "2.568.2")));

        Path configFile = fixture("header-app.yaml");

        int exitCode = new CommandLine(new ConfigCommand()).execute(configFile.toString());

        String output = capturedOut.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("HEADER: OK"),
                "a header that resolves on a 403 response must still be reported as RAN/OK for a "
                        + "real, disk-parsed http-header app; output was:\n" + output);
        assertEquals(ValidationOutcome.ConfigFileResult.ALL_OK_EXIT_CODE, exitCode);
    }
}
