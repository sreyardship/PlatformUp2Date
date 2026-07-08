package org.yardship.confcheck.command;

import org.yardship.confcheck.render.ReportRenderer;
import org.yardship.confcheck.validation.CalverFormatValidation;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * {@code cli calver --format <fmt> --version <v>}
 *
 * <p>Like {@link ChangelogCommand}, this subcommand is a PURE-FUNCTION check — no
 * {@link org.yardship.confcheck.port.BodySource}, no {@code --url}/{@code --body-file}/stdin, no
 * {@code ArgGroup}. Unlike {@code changelog} there is no {@code --scheme} flag: the scheme is
 * implicitly {@code CALVER}, so {@link org.yardship.confcheck.validation.CalverFormatValidation} builds
 * its own {@code CalverFormat}/{@code VersionParser} internally from {@code --format}.
 */
@Command(name = "calver", description = "Validate a calver-format spec against --version, printing its token=value mapping.")
public final class CalverCommand implements Callable<Integer> {

    @Option(names = "--format", required = true, description = "The calver.org format string, e.g. YY.0M.MICRO.")
    String format;

    @Option(names = "--version", required = true, description = "A sample version to resolve against --format.")
    String version;

    private final ReportRenderer renderer = new ReportRenderer();

    @Override
    public Integer call() {
        var outcome = new CalverFormatValidation().validate(format, version);
        return renderer.render(outcome, System.out);
    }
}
