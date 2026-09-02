package org.yardship.confcheck.command;

import org.yardship.confcheck.adapter.LiveHttpResponseSource;
import org.yardship.confcheck.adapter.OfflineResponseSource;
import org.yardship.confcheck.outcome.ValidationOutcome;
import org.yardship.confcheck.port.BodySource;
import org.yardship.confcheck.port.ResponseSource;
import org.yardship.confcheck.render.ReportRenderer;
import org.yardship.confcheck.validation.HeaderExtractionValidation;
import org.yardship.confcheck.version.VersionSpec;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * {@code cli header --header <name> [--regex '<pattern>'] [--scheme semver|calver
 * [--calver-format <fmt>]] (--url <U> | --offline [--status <code>] [--header-value NAME=VALUE]...)}
 *
 * <p>Validates an {@code http-header} current source's {@code version-header} (and, when a
 * {@code regex} is configured, its first-match extraction) before deploying it — the header
 * surface's own composition-root shape, mirroring {@link PointerCommand}'s: response source
 * selection via {@link org.yardship.confcheck.port.ResponseSource} (live {@code --url} fetch via
 * {@link LiveHttpResponseSource}, or offline {@code --status}/{@code --header-value} fixtures via
 * {@link OfflineResponseSource} — exactly one required), an OPTIONAL {@code --scheme} (as for
 * {@code pointer}: checking that the header merely resolves is useful on its own), validation via
 * {@link HeaderExtractionValidation}, render via {@link ReportRenderer}, return exit code.
 *
 * <p>Unlike {@code regex}/{@code pointer}, the offline path here is NOT file/stdin-backed: a
 * fixture response is built entirely from CLI flags ({@code --status}, repeatable
 * {@code --header-value NAME=VALUE}), since what needs faking is a whole response (status +
 * headers), not a body read from disk.
 */
@Command(name = "header", description = "Validate an http-header current source's version-header against a response.")
public final class HeaderCommand implements Callable<Integer> {

    @Option(names = "--header", required = true, description = "The response header name to read (case-insensitive).")
    String header;

    @Option(names = "--regex", description = "Optional Java regex with capture group 1 as the version token; first match wins.")
    String regex;

    @Option(names = "--strip-prerelease", description = "Apply VersionValue.withoutPreRelease() semantics to the parsed value.")
    boolean stripPreRelease;

    @Option(names = "--scheme", description = "semver | calver. Optional: omit to only check header resolution.")
    String scheme;

    @Option(names = "--calver-format", description = "Required when --scheme=calver.")
    String calverFormat;

    @ArgGroup(exclusive = true, multiplicity = "1", heading = "Exactly one response source is required.%n")
    ResponseSourceOption responseSourceOption;

    static final class ResponseSourceOption {
        @Option(names = "--url", description = "Fetch the response live from this URL.")
        String url;

        @Option(names = "--offline", description = "Use an offline fixture response (--status/--header-value).")
        boolean offline;
    }

    @Option(names = "--status", defaultValue = "200", description = "Offline fixture status code (default 200).")
    int status;

    @Option(names = "--header-value", description = "Offline fixture header, as NAME=VALUE. Repeatable.")
    List<String> headerValues = new ArrayList<>();

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

        ResponseSource responseSource;
        try {
            responseSource = selectResponseSource();
        } catch (IllegalArgumentException e) {
            return renderer.render(new ValidationOutcome.ConfigInvalid(e.getMessage()), System.out);
        }

        ResponseSource.Response response;
        try {
            response = responseSource.fetch();
        } catch (BodySource.BodyFetchException e) {
            return renderer.render(new ValidationOutcome.FetchFailed(e.getMessage()), System.out);
        }

        ValidationOutcome outcome = new HeaderExtractionValidation()
                .validate(response, header, Optional.ofNullable(regex), stripPreRelease, parser);
        return renderer.render(outcome, System.out);
    }

    private ResponseSource selectResponseSource() {
        if (responseSourceOption.url != null) {
            return new LiveHttpResponseSource(responseSourceOption.url);
        }
        return OfflineResponseSource.of(status, parseHeaderValues());
    }

    private Map<String, List<String>> parseHeaderValues() {
        Map<String, List<String>> headers = new HashMap<>();
        for (String entry : headerValues) {
            int separator = entry.indexOf('=');
            if (separator < 0) {
                throw new IllegalArgumentException(
                        "--header-value must be in NAME=VALUE form; was: '" + entry + "'");
            }
            String name = entry.substring(0, separator);
            String value = entry.substring(separator + 1);
            headers.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
        }
        return headers;
    }
}
