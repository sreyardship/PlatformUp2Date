package org.yardship.confcheck.validation;

import org.yardship.confcheck.outcome.AppValidationResult;
import org.yardship.confcheck.outcome.SurfaceResult;
import org.yardship.confcheck.outcome.ValidationOutcome;
import org.yardship.confcheck.port.AppConfig;
import org.yardship.confcheck.port.BodySource;
import org.yardship.confcheck.version.VersionSpec;
import org.yardship.core.domain.primitives.CalverFormat;
import org.yardship.core.domain.primitives.VersionParser;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * The {@code config} gate's composing use case (issue 06): runs the four per-surface validators
 * (regex, pointer, changelog-template, calver-format) against every {@link AppConfig} in a parsed
 * {@code platform-config.yaml}, aggregating into a single {@link ValidationOutcome.ConfigFileResult}.
 *
 * <p>Reuses, per app, exactly the validators already proven by issues 02-05 — never their
 * production-only, opaque-result siblings:
 * <ul>
 *   <li>{@link RegexExtractionValidation} against {@code latest.url}'s live-fetched body, when
 *       {@code latest.type == "http-regex"} and both {@code latest.url}/{@code latest.regex} are
 *       configured.</li>
 *   <li>{@link PointerExtractionValidation} against {@code current.url}'s live-fetched body, when
 *       {@code current.type == "http"}. {@code current.version-key}'s documented
 *       {@code "/version"} default (see {@link AppConfig#currentVersionKey()}'s javadoc) is applied
 *       HERE, not by the reader.</li>
 *   <li>{@code new org.yardship.core.domain.primitives.ChangelogTemplate(...)}'s constructor ONLY
 *       (never {@link ChangelogResolutionValidation}, which needs a live/sample version to
 *       {@code .resolve()} against that a static config file does not have) — see
 *       {@link ValidationOutcome.ChangelogTemplateValid}'s design note.</li>
 *   <li>{@code new org.yardship.core.domain.primitives.CalverFormat(...)}'s constructor ONLY
 *       (never {@link CalverFormatValidation}, same reasoning) — see
 *       {@link ValidationOutcome.CalverFormatValid}'s design note.</li>
 * </ul>
 *
 * <p>{@code offline} suppresses ONLY the two live-fetch surfaces (regex, pointer) — those become
 * {@link org.yardship.confcheck.outcome.SurfaceResult#skippedOffline}. changelog/calver are pure-function
 * checks with no body source and are never skipped by {@code offline}. A surface with nothing
 * configured for it on a given app (e.g. {@code latest.type == "github-release"} for the regex
 * surface) is {@link org.yardship.confcheck.outcome.SurfaceResult#notApplicable}, independent of
 * {@code offline}.
 */
public final class ConfigFileValidation {

    private static final String HTTP_CURRENT_TYPE = "http";
    private static final String HTTP_REGEX_LATEST_TYPE = "http-regex";
    private static final String DEFAULT_POINTER = "/version";

    private final RegexExtractionValidation regexValidation = new RegexExtractionValidation();
    private final PointerExtractionValidation pointerValidation = new PointerExtractionValidation();
    private final Function<String, BodySource> bodySourceFactory;

    /**
     * @param bodySourceFactory the body-fetch seam: given a URL, produces the {@link BodySource} to
     *                          fetch it with. The composition root ({@link
     *                          org.yardship.confcheck.command.ConfigCommand}) supplies the concrete adapter
     *                          (e.g. {@code LiveHttpBodySource::new}); this class only depends on the
     *                          {@link BodySource} port, never a concrete adapter.
     */
    public ConfigFileValidation(Function<String, BodySource> bodySourceFactory) {
        this.bodySourceFactory = bodySourceFactory;
    }

    /**
     * @param apps    every app to validate, in file order (mirrors {@link AppConfig} order from
     *                {@code AppConfigReader.apps()}).
     * @param offline when {@code true}, the regex and pointer surfaces (both live-fetch-backed) are
     *                skipped ({@code SKIPPED_OFFLINE}) for every app rather than fetched; changelog
     *                and calver surfaces are unaffected.
     * @return the aggregate {@link ValidationOutcome.ConfigFileResult}, one
     *         {@link org.yardship.confcheck.outcome.AppValidationResult} per app in {@code apps}, in the
     *         same order.
     */
    public ValidationOutcome.ConfigFileResult validate(List<AppConfig> apps, boolean offline) {
        List<AppValidationResult> results = apps.stream()
                .map(app -> validateApp(app, offline))
                .toList();
        return new ValidationOutcome.ConfigFileResult(results);
    }

    private AppValidationResult validateApp(AppConfig app, boolean offline) {
        SurfaceResult regex = regexSurface(app, offline);
        SurfaceResult pointer = pointerSurface(app, offline);
        SurfaceResult changelog = changelogSurface(app);
        SurfaceResult calver = calverSurface(app);
        return new AppValidationResult(app.name(), List.of(regex, pointer, changelog, calver));
    }

    private SurfaceResult regexSurface(AppConfig app, boolean offline) {
        boolean applicable = HTTP_REGEX_LATEST_TYPE.equals(app.latestType())
                && app.latestUrl().isPresent()
                && app.latestRegex().isPresent();
        if (!applicable) {
            return SurfaceResult.notApplicable(SurfaceResult.Surface.REGEX);
        }
        if (offline) {
            return SurfaceResult.skippedOffline(SurfaceResult.Surface.REGEX);
        }

        VersionParser parser;
        try {
            parser = buildParser(app);
        } catch (VersionSpec.VersionSpecException e) {
            return SurfaceResult.ran(SurfaceResult.Surface.REGEX, new ValidationOutcome.ConfigInvalid(e.getMessage()));
        }

        String body;
        try {
            body = fetch(app.latestUrl().orElseThrow());
        } catch (BodySource.BodyFetchException e) {
            return SurfaceResult.ran(SurfaceResult.Surface.REGEX, new ValidationOutcome.FetchFailed(e.getMessage()));
        }

        ValidationOutcome outcome = regexValidation.validate(body, app.latestRegex().orElseThrow(), parser);
        return SurfaceResult.ran(SurfaceResult.Surface.REGEX, outcome);
    }

    private SurfaceResult pointerSurface(AppConfig app, boolean offline) {
        boolean applicable = HTTP_CURRENT_TYPE.equals(app.currentType());
        if (!applicable) {
            return SurfaceResult.notApplicable(SurfaceResult.Surface.POINTER);
        }
        if (offline) {
            return SurfaceResult.skippedOffline(SurfaceResult.Surface.POINTER);
        }

        VersionParser parser;
        try {
            parser = buildParser(app);
        } catch (VersionSpec.VersionSpecException e) {
            return SurfaceResult.ran(SurfaceResult.Surface.POINTER, new ValidationOutcome.ConfigInvalid(e.getMessage()));
        }

        String body;
        try {
            body = fetch(app.currentUrl().orElseThrow());
        } catch (BodySource.BodyFetchException e) {
            return SurfaceResult.ran(SurfaceResult.Surface.POINTER, new ValidationOutcome.FetchFailed(e.getMessage()));
        }

        String pointer = app.currentVersionKey().orElse(DEFAULT_POINTER);
        ValidationOutcome outcome = pointerValidation.validate(
                body, pointer, app.currentStripPrerelease(), Optional.of(parser));
        return SurfaceResult.ran(SurfaceResult.Surface.POINTER, outcome);
    }

    private SurfaceResult changelogSurface(AppConfig app) {
        if (app.changelogUrl().isEmpty()) {
            return SurfaceResult.notApplicable(SurfaceResult.Surface.CHANGELOG);
        }

        String changelogUrl = app.changelogUrl().get();
        try {
            Optional<CalverFormat> calverFormat = app.calverFormat().map(CalverFormat::new);
            new org.yardship.core.domain.primitives.ChangelogTemplate(changelogUrl, app.versionScheme(), calverFormat);
            return SurfaceResult.ran(
                    SurfaceResult.Surface.CHANGELOG, new ValidationOutcome.ChangelogTemplateValid(changelogUrl));
        } catch (IllegalArgumentException e) {
            return SurfaceResult.ran(SurfaceResult.Surface.CHANGELOG, new ValidationOutcome.ConfigInvalid(e.getMessage()));
        }
    }

    private SurfaceResult calverSurface(AppConfig app) {
        boolean applicable = app.versionScheme() == org.yardship.core.domain.primitives.VersionScheme.CALVER
                && app.calverFormat().isPresent();
        if (!applicable) {
            return SurfaceResult.notApplicable(SurfaceResult.Surface.CALVER);
        }

        String format = app.calverFormat().get();
        try {
            new CalverFormat(format);
            return SurfaceResult.ran(SurfaceResult.Surface.CALVER, new ValidationOutcome.CalverFormatValid(format));
        } catch (IllegalArgumentException e) {
            return SurfaceResult.ran(SurfaceResult.Surface.CALVER, new ValidationOutcome.ConfigInvalid(e.getMessage()));
        }
    }

    private VersionParser buildParser(AppConfig app) {
        return VersionSpec.of(app.versionScheme(), app.calverFormat().orElse(null)).parser();
    }

    private String fetch(String url) {
        return bodySourceFactory.apply(url).body();
    }
}
