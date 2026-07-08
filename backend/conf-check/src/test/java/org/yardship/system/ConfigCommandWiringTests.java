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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end wiring test: invokes the real {@code config} picocli command against the
 * {@code mixed-apps.yaml} fixture with {@code --offline} (so this test needs no live network/WireMock
 * wiring — the finer-grained live-fetch coverage lives in
 * {@code org.yardship.unit.validation.ConfigFileValidationTests}). Proves the command wires
 * {@code YamlAppConfigReader -> ConfigFileValidation -> ReportRenderer} together and that the
 * aggregate exit code actually reaches the process, mirroring {@code RegexCommandWiringTests}.
 */
class ConfigCommandWiringTests {

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

    @Test
    void offline_mixedApps_printsPerAppLinesAndAllPassOrSkipped() throws URISyntaxException {
        Path configFile = fixture("mixed-apps.yaml");

        int exitCode = new CommandLine(new ConfigCommand()).execute(configFile.toString(), "--offline");

        String output = capturedOut.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("chaos-social"), "output must mention app 'chaos-social'");
        assertTrue(output.contains("openwrt-router"), "output must mention app 'openwrt-router'");
        assertTrue(output.contains("gitea-oci"), "output must mention app 'gitea-oci'");
        // Every applicable surface either passes (changelog/calver, never skipped) or is skipped
        // offline (regex/pointer); nothing in this fixture is misconfigured, so the whole file must
        // pass and exit 0 (ConfigFileResult.ALL_OK_EXIT_CODE).
        assertEquals(ValidationOutcome.ConfigFileResult.ALL_OK_EXIT_CODE, exitCode);
    }

    @Test
    void malformedFile_isConfigInvalid() throws URISyntaxException {
        Path configFile = fixture("malformed.yaml");

        int exitCode = new CommandLine(new ConfigCommand()).execute(configFile.toString(), "--offline");

        assertEquals(ValidationOutcome.ConfigInvalid.EXIT_CODE, exitCode,
                "an unreadable/malformed config file must map to ConfigInvalid, distinct from "
                        + "'the file parsed but some app failed' (SOME_FAILED_EXIT_CODE)");
    }

    @Test
    void missingFileArgument_isUsageError() {
        int exitCode = new CommandLine(new ConfigCommand()).execute("--offline");

        assertEquals(CommandLine.ExitCode.USAGE, exitCode,
                "the positional <file> parameter is required; omitting it is a usage error, before any validation runs");
    }
}
