package org.yardship.confcheck.command;

import org.yardship.confcheck.adapter.LiveHttpBodySource;
import org.yardship.confcheck.adapter.OfflineBodySource;
import org.yardship.confcheck.outcome.ValidationOutcome;
import org.yardship.confcheck.port.BodySource;
import org.yardship.confcheck.render.ReportRenderer;
import org.yardship.confcheck.validation.RegexExtractionValidation;
import org.yardship.confcheck.version.VersionSpec;
import org.yardship.core.domain.primitives.VersionScheme;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code cli regex --regex '<pattern>' --scheme semver|calver [--calver-format <fmt>]
 * (--url <U> | --body-file <F> | -)}
 *
 * <p>Obtains the body via the {@link org.yardship.confcheck.port.BodySource} port (live {@code --url}
 * fetch via {@link org.yardship.confcheck.adapter.LiveHttpBodySource}, or offline
 * {@code --body-file}/stdin via {@link org.yardship.confcheck.adapter.OfflineBodySource} — exactly one
 * required), builds a {@link org.yardship.confcheck.version.VersionSpec} from {@code --scheme}/
 * {@code --calver-format}, runs {@link org.yardship.confcheck.validation.RegexExtractionValidation}, and
 * renders the {@link org.yardship.confcheck.outcome.ValidationOutcome} via
 * {@link org.yardship.confcheck.render.ReportRenderer}, returning its exit code.
 *
 * <p>Zero/two body sources are rejected by picocli's own {@code ArgGroup} validation before
 * {@link #call()} ever runs (exit code {@link picocli.CommandLine.ExitCode#USAGE}, which is
 * numerically 2 — the same value as {@link ValidationOutcome.ConfigInvalid#EXIT_CODE}), so that
 * failure mode never reaches {@link ValidationOutcome}.
 */
@Command(name = "regex", description = "Validate an http-regex 'regex' against a body.")
public final class RegexCommand implements Callable<Integer> {

    @Option(names = "--regex", required = true, description = "Java regex with capture group 1 as the version token.")
    String regex;

    @Option(names = "--scheme", required = true, description = "semver | calver")
    String scheme;

    @Option(names = "--calver-format", description = "Required when --scheme=calver.")
    String calverFormat;

    @ArgGroup(exclusive = true, multiplicity = "1", heading = "Exactly one body source is required.%n")
    BodySourceOption bodySourceOption;

    static final class BodySourceOption {
        @Option(names = "--url", description = "Fetch the body live from this URL.")
        String url;

        @Option(names = "--body-file", description = "Read the body from this file.")
        Path bodyFile;

        @Option(names = "-", description = "Read the body from stdin.")
        boolean stdin;
    }

    private final ReportRenderer renderer = new ReportRenderer();

    @Override
    public Integer call() {
        VersionSpec versionSpec;
        try {
            versionSpec = VersionSpec.of(VersionScheme.valueOf(scheme.toUpperCase()), calverFormat);
        } catch (VersionSpec.VersionSpecException | IllegalArgumentException e) {
            return renderer.render(new ValidationOutcome.ConfigInvalid(e.getMessage()), System.out);
        }

        BodySource bodySource = selectBodySource();

        String body;
        try {
            body = bodySource.body();
        } catch (BodySource.BodyFetchException e) {
            return renderer.render(new ValidationOutcome.FetchFailed(e.getMessage()), System.out);
        }

        ValidationOutcome outcome = new RegexExtractionValidation()
                .validate(body, regex, versionSpec.parser());
        return renderer.render(outcome, System.out);
    }

    private BodySource selectBodySource() {
        if (bodySourceOption.url != null) {
            return new LiveHttpBodySource(bodySourceOption.url);
        }
        if (bodySourceOption.bodyFile != null) {
            return OfflineBodySource.fromFile(bodySourceOption.bodyFile);
        }
        return OfflineBodySource.fromStream(System.in);
    }
}
