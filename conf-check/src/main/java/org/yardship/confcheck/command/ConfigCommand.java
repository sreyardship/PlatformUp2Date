package org.yardship.confcheck.command;

import org.yardship.confcheck.adapter.LiveHttpBodySource;
import org.yardship.confcheck.adapter.YamlAppConfigReader;
import org.yardship.confcheck.outcome.ValidationOutcome;
import org.yardship.confcheck.port.AppConfigReader;
import org.yardship.confcheck.render.ReportRenderer;
import org.yardship.confcheck.validation.ConfigFileValidation;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code cli config <file> [--offline]}
 *
 * <p>The {@code config} gate (issue 06): reads every app out of a {@code platform-config.yaml} via
 * {@link YamlAppConfigReader}, runs {@link ConfigFileValidation} across all of them, and renders the
 * aggregate {@link org.yardship.confcheck.outcome.ValidationOutcome.ConfigFileResult} via
 * {@link ReportRenderer} — same composition-root shape as every other subcommand
 * ({@code BodySource}/reader -> use case -> renderer -> exit code), but operating on a whole FILE of
 * apps rather than a single {@code --url}/{@code --body-file} invocation.
 *
 * <p>An unreadable/malformed file ({@link AppConfigReader.ConfigReadException}, thrown by
 * {@link YamlAppConfigReader#apps()}) is mapped to {@link ValidationOutcome.ConfigInvalid} — the
 * file itself is wrong, distinct from "the file parsed but some app inside it failed validation"
 * (which is {@link ValidationOutcome.ConfigFileResult}'s {@code SOME_FAILED_EXIT_CODE}; see that
 * record's design note).
 */
@Command(name = "config", description = "Validate every app's config-gate-relevant surfaces in a platform-config.yaml file.")
public final class ConfigCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Path to the platform-config.yaml file to validate.")
    Path file;

    @Option(names = "--offline", description = "Skip live-fetch surfaces (regex/pointer); changelog/calver still run.")
    boolean offline;

    private final ReportRenderer renderer = new ReportRenderer();

    @Override
    public Integer call() {
        AppConfigReader reader = new YamlAppConfigReader(file);
        try {
            ValidationOutcome.ConfigFileResult result =
                    new ConfigFileValidation(LiveHttpBodySource::new).validate(reader.apps(), offline);
            return renderer.render(result, System.out);
        } catch (AppConfigReader.ConfigReadException e) {
            return renderer.render(new ValidationOutcome.ConfigInvalid(e.getMessage()), System.out);
        }
    }
}
