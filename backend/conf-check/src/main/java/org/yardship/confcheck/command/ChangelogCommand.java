package org.yardship.confcheck.command;

import org.yardship.confcheck.outcome.ValidationOutcome;
import org.yardship.confcheck.render.ReportRenderer;
import org.yardship.confcheck.validation.ChangelogResolutionValidation;
import org.yardship.confcheck.version.VersionSpec;
import org.yardship.core.domain.primitives.VersionScheme;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * {@code cli changelog --template <url-template> --version <v> --scheme semver|calver
 * [--calver-format <fmt>]}
 *
 * <p>Unlike {@link RegexCommand}/{@link PointerCommand}, this subcommand is a PURE-FUNCTION
 * check — no {@link org.yardship.confcheck.port.BodySource}, no {@code --url}/{@code --body-file}/stdin,
 * no {@code ArgGroup} for a body source. It builds a {@link org.yardship.confcheck.version.VersionSpec}
 * from {@code --scheme}/{@code --calver-format}, runs
 * {@link org.yardship.confcheck.validation.ChangelogResolutionValidation}, and renders the
 * {@link org.yardship.confcheck.outcome.ValidationOutcome} via {@link ReportRenderer}, returning its
 * exit code — same composition-root shape as {@code RegexCommand#call()} minus the body-source
 * step.
 */
@Command(name = "changelog", description = "Validate a changelog-url template against --version.")
public final class ChangelogCommand implements Callable<Integer> {

    @Option(names = "--template", required = true, description = "The changelog-url template, e.g. https://x/{major}.{minor}.")
    String template;

    @Option(names = "--version", required = true, description = "The version to resolve the template against.")
    String version;

    @Option(names = "--scheme", required = true, description = "semver | calver")
    String scheme;

    @Option(names = "--calver-format", description = "Required when --scheme=calver.")
    String calverFormat;

    private final ReportRenderer renderer = new ReportRenderer();

    @Override
    public Integer call() {
        VersionSpec versionSpec;
        try {
            versionSpec = VersionSpec.of(VersionScheme.valueOf(scheme.toUpperCase()), calverFormat);
        } catch (VersionSpec.VersionSpecException | IllegalArgumentException e) {
            return renderer.render(new ValidationOutcome.ConfigInvalid(e.getMessage()), System.out);
        }

        ValidationOutcome outcome = new ChangelogResolutionValidation()
                .validate(template, version, versionSpec.parser(), versionSpec.calverFormat());
        return renderer.render(outcome, System.out);
    }
}
