package org.yardship.confcheck.command;

import org.yardship.confcheck.adapter.LiveHttpBodySource;
import org.yardship.confcheck.adapter.OfflineBodySource;
import org.yardship.confcheck.outcome.ValidationOutcome;
import org.yardship.confcheck.port.BodySource;
import org.yardship.confcheck.render.ReportRenderer;
import org.yardship.confcheck.validation.MetricExtractionValidation;
import org.yardship.confcheck.version.VersionSpec;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code cli metric --metric <name> [--label NAME=VALUE]... [--version-label <name>]
 * [--regex '<pattern>'] [--strip-prerelease] [--scheme semver|calver [--calver-format <fmt>]]
 * (--url <U> | --body-file <F> | -)}
 *
 * <p>Validates an {@code http-prometheus} current source's {@code metric} / {@code labels:}
 * selector / {@code version-label} (+ optional {@code regex}) before deploying it — see
 * {@code docs/adr/0033-http-prometheus-current-source.md}, the binding specification for this
 * kind.
 *
 * <p>Follows {@link RegexCommand}'s {@link BodySource} shape (file / stdin / live URL), NOT
 * {@link HeaderCommand}'s {@code ResponseSource} shape — what needs faking here is a body (a saved
 * {@code curl /metrics > blackbox.txt} fixture), not a status-plus-headers response: obtains the
 * body via {@link BodySource} (live {@code --url} fetch via {@link LiveHttpBodySource}, or offline
 * {@code --body-file}/stdin via {@link OfflineBodySource} — exactly one required), builds an
 * OPTIONAL {@link VersionSpec} from {@code --scheme}/{@code --calver-format} (as for
 * {@code pointer}/{@code header}: checking that the metric and selector merely resolve to a value
 * is useful on its own), runs {@link MetricExtractionValidation}, and renders the
 * {@link ValidationOutcome} via {@link ReportRenderer}, returning its exit code.
 *
 * <p>{@code --label} is repeatable and takes {@code NAME=VALUE}, mirroring {@link HeaderCommand}'s
 * {@code --header-value}.
 *
 * <p>Zero/two body sources are rejected by picocli's own {@code ArgGroup} validation before
 * {@link #call()} ever runs (exit code {@link picocli.CommandLine.ExitCode#USAGE}, numerically 2 —
 * the same value as {@link ValidationOutcome.ConfigInvalid#EXIT_CODE}), so that failure mode never
 * reaches {@link ValidationOutcome}, mirroring {@link RegexCommand}.
 */
@Command(name = "metric", description = "Validate an http-prometheus current source's metric/labels/version-label against a body.")
public final class MetricCommand implements Callable<Integer> {

    @Option(names = "--metric", required = true, description = "The exact Prometheus metric name to match.")
    String metric;

    @Option(names = "--label", description = "Installation selector entry, as NAME=VALUE. Repeatable; ANDed; exact match.")
    List<String> labels = new ArrayList<>();

    @Option(names = "--version-label", defaultValue = "version", description = "The label to read the version from (default 'version').")
    String versionLabel;

    @Option(names = "--regex", description = "Optional Java regex with capture group 1 as the version token; first parseable match wins.")
    String regex;

    @Option(names = "--strip-prerelease", description = "Apply VersionValue.withoutPreRelease() semantics to the parsed value.")
    boolean stripPreRelease;

    @Option(names = "--scheme", description = "semver | calver. Optional: omit to only check metric/selector/label resolution.")
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

        Map<String, String> labelSelector;
        try {
            labelSelector = parseLabels();
        } catch (IllegalArgumentException e) {
            return renderer.render(new ValidationOutcome.ConfigInvalid(e.getMessage()), System.out);
        }

        BodySource bodySource = selectBodySource();

        String body;
        try {
            body = bodySource.body();
        } catch (BodySource.BodyFetchException e) {
            return renderer.render(new ValidationOutcome.FetchFailed(e.getMessage()), System.out);
        }

        ValidationOutcome outcome = new MetricExtractionValidation()
                .validate(body, metric, labelSelector, versionLabel, Optional.ofNullable(regex), stripPreRelease, parser);
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

    private Map<String, String> parseLabels() {
        Map<String, String> labelSelector = new LinkedHashMap<>();
        for (String entry : labels) {
            int separator = entry.indexOf('=');
            if (separator < 0) {
                throw new IllegalArgumentException("--label must be in NAME=VALUE form; was: '" + entry + "'");
            }
            String name = entry.substring(0, separator);
            String value = entry.substring(separator + 1);
            labelSelector.put(name, value);
        }
        return labelSelector;
    }
}
