package org.yardship.cli.command;

import org.yardship.cli.adapter.LiveHttpBodySource;
import org.yardship.cli.adapter.OfflineBodySource;
import org.yardship.cli.outcome.ValidationOutcome;
import org.yardship.cli.port.BodySource;
import org.yardship.cli.render.ReportRenderer;
import org.yardship.cli.validation.PointerExtractionValidation;
import org.yardship.cli.version.VersionSpec;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code cli pointer --key <json-pointer> [--strip-prerelease] [--scheme semver|calver
 * [--calver-format <fmt>]] (--url <U> | --body-file <F> | -)}
 *
 * <p>Will mirror {@link RegexCommand}'s composition-root shape (body source selection via
 * {@link org.yardship.cli.port.BodySource}, validation via
 * {@link org.yardship.cli.validation.PointerExtractionValidation}, render via
 * {@link org.yardship.cli.render.ReportRenderer}, return exit code), with one difference:
 * {@code --scheme} is OPTIONAL here — the pointer subcommand can run purely to check that a JSON
 * Pointer resolves to some text, without also validating it parses under a scheme. When
 * {@code --scheme} is absent, no {@link org.yardship.cli.version.VersionSpec} should be built at
 * all (constructing one always requires a scheme); {@code call()}'s implementation should pass an
 * empty parser to {@code PointerExtractionValidation} in that case.
 *
 * <p>Same composition pattern as {@link RegexCommand#call()}, with an extra branch: a
 * {@link VersionSpec} (and therefore a {@link VersionParser}) is only built when {@code --scheme}
 * was actually supplied; otherwise an empty parser is passed to
 * {@link org.yardship.cli.validation.PointerExtractionValidation}, running an extraction-only check.
 */
@Command(name = "pointer", description = "Validate an http current source's version-key (JSON Pointer) against a body.")
public final class PointerCommand implements Callable<Integer> {

    @Option(names = "--key", required = true, description = "RFC 6901 JSON Pointer, e.g. /version.")
    String key;

    @Option(names = "--strip-prerelease", description = "Apply VersionValue.withoutPreRelease() semantics to the extracted text.")
    boolean stripPreRelease;

    @Option(names = "--scheme", description = "semver | calver. Optional: omit to only check pointer resolution.")
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
        if (stripPreRelease && scheme == null) {
            return renderer.render(
                    new ValidationOutcome.ConfigInvalid("--strip-prerelease requires --scheme"), System.out);
        }

        Optional<VersionParser> parser;
        try {
            parser = (scheme == null)
                    ? Optional.empty()
                    : Optional.of(VersionSpec.of(VersionScheme.valueOf(scheme.toUpperCase()), calverFormat).parser());
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

        ValidationOutcome outcome = new PointerExtractionValidation()
                .validate(body, key, stripPreRelease, parser);
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
