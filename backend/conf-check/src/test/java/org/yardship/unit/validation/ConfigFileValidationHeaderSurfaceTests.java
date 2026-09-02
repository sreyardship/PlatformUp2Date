package org.yardship.unit.validation;

import org.junit.jupiter.api.Test;
import org.yardship.confcheck.outcome.AppValidationResult;
import org.yardship.confcheck.outcome.SurfaceResult;
import org.yardship.confcheck.outcome.ValidationOutcome;
import org.yardship.confcheck.port.AppConfig;
import org.yardship.confcheck.port.BodySource;
import org.yardship.confcheck.port.ResponseSource;
import org.yardship.confcheck.validation.ConfigFileValidation;
import org.yardship.core.domain.primitives.VersionScheme;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@code config} gate's NEW {@code header} surface, added in slice 04
 * (deliberately a separate file from {@code ConfigFileValidationTests} — that file is pre-existing
 * and must not be modified). Exercises {@link ConfigFileValidation} through its
 * {@link ResponseSource} port with a fake, exactly as {@code ConfigFileValidationTests} exercises
 * the regex/pointer surfaces through the {@link BodySource} port with a fake — no real HTTP, no
 * WireMock, no network.
 *
 * <p>This class assumes {@link ConfigFileValidation} gains a second constructor parameter, a
 * {@code Function<String, ResponseSource>} response-source factory, alongside its existing
 * {@code Function<String, BodySource>} body-source factory — the same seam-injection pattern the
 * regex/pointer surfaces already use, extended for the one surface ({@code header}) that needs a
 * whole {@link ResponseSource.Response} (status + headers) rather than just a body.
 */
class ConfigFileValidationHeaderSurfaceTests {

    private static final String HEADER_NAME = "X-Jenkins";

    /** A body-source factory that fails the test immediately if it is ever invoked. */
    private static final Function<String, BodySource> BODY_NEVER_INVOKED =
            url -> { throw new AssertionError("bodySourceFactory must not be invoked, but was invoked for url: " + url); };

    @Test
    void httpHeaderApp_headerSurfaceRuns_andPasses_whenHeaderResolves() {
        String url = "http://example.test/";
        ConfigFileValidation validation = new ConfigFileValidation(
                BODY_NEVER_INVOKED, fakeResponseSource(Map.of(url, response(200, Map.of(HEADER_NAME, List.of("2.568.2"))))));

        AppConfig app = httpHeaderApp(url, HEADER_NAME, Optional.empty());

        ValidationOutcome.ConfigFileResult result = validation.validate(List.of(app), false);

        AppValidationResult appResult = result.apps().get(0);
        assertFalse(appResult.isFailure());
        SurfaceResult header = surface(appResult, SurfaceResult.Surface.HEADER);
        assertEquals(SurfaceResult.Status.RAN, header.status());
        assertTrue(header.outcome().orElseThrow() instanceof ValidationOutcome.HeaderOk);
    }

    @Test
    void httpHeaderApp_headerAbsentFromResponse_isFailure() {
        String url = "http://example.test/";
        ConfigFileValidation validation = new ConfigFileValidation(
                BODY_NEVER_INVOKED, fakeResponseSource(Map.of(url, response(403, Map.of()))));

        AppConfig app = httpHeaderApp(url, HEADER_NAME, Optional.empty());

        ValidationOutcome.ConfigFileResult result = validation.validate(List.of(app), false);

        AppValidationResult appResult = result.apps().get(0);
        assertTrue(appResult.isFailure());
        SurfaceResult header = surface(appResult, SurfaceResult.Surface.HEADER);
        assertTrue(header.outcome().orElseThrow() instanceof ValidationOutcome.HeaderValidButEmpty);
        assertEquals(ValidationOutcome.ConfigFileResult.SOME_FAILED_EXIT_CODE, result.exitCode());
    }

    @Test
    void httpHeaderApp_on403Response_headerStillResolves_isNotAFailure() {
        // The ADR-0030 motivating case, exercised through the config gate: a secured Jenkins
        // refuses the page (403) but volunteers its version anyway. The config gate must not treat
        // this as a failure.
        String url = "http://example.test/";
        ConfigFileValidation validation = new ConfigFileValidation(
                BODY_NEVER_INVOKED,
                fakeResponseSource(Map.of(url, response(403, Map.of(HEADER_NAME, List.of("2.568.2"))))));

        AppConfig app = httpHeaderApp(url, HEADER_NAME, Optional.empty());

        ValidationOutcome.ConfigFileResult result = validation.validate(List.of(app), false);

        AppValidationResult appResult = result.apps().get(0);
        assertFalse(appResult.isFailure(),
                "a header that resolves on a 403 response must pass — this is the exact case "
                        + "http-header exists for");
    }

    @Test
    void httpHeaderApp_withConfiguredRegex_firstMatchWins() {
        String url = "http://example.test/";
        ConfigFileValidation validation = new ConfigFileValidation(
                BODY_NEVER_INVOKED,
                fakeResponseSource(
                        Map.of(url, response(200, Map.of(HEADER_NAME, List.of("1.0.0 then later 9.9.9"))))));

        AppConfig app = httpHeaderApp(url, HEADER_NAME, Optional.of("(\\d+\\.\\d+\\.\\d+)"));

        ValidationOutcome.ConfigFileResult result = validation.validate(List.of(app), false);

        AppValidationResult appResult = result.apps().get(0);
        assertFalse(appResult.isFailure());
        ValidationOutcome.HeaderOk ok =
                (ValidationOutcome.HeaderOk) surface(appResult, SurfaceResult.Surface.HEADER).outcome().orElseThrow();
        assertEquals("1.0.0", ok.result().parsed().orElseThrow().value(),
                "the config gate's header surface must use the FIRST match, matching slice 03 exactly");
    }

    @Test
    void offline_skipsHeaderSurface_responseSourceFactoryNeverInvoked() {
        String url = "http://example.test/";
        ConfigFileValidation validation = new ConfigFileValidation(BODY_NEVER_INVOKED,
                u -> { throw new AssertionError("responseSourceFactory must not be invoked when offline, but was invoked for: " + u); });

        AppConfig app = httpHeaderApp(url, HEADER_NAME, Optional.empty());

        ValidationOutcome.ConfigFileResult result = validation.validate(List.of(app), true);

        AppValidationResult appResult = result.apps().get(0);
        assertEquals(SurfaceResult.Status.SKIPPED_OFFLINE, surface(appResult, SurfaceResult.Surface.HEADER).status());
        assertFalse(appResult.isFailure());
    }

    @Test
    void nonHttpHeaderApp_headerSurfaceIsNotApplicable_responseSourceFactoryNeverInvoked() {
        ConfigFileValidation validation = new ConfigFileValidation(
                url -> () -> "irrelevant-body",
                u -> { throw new AssertionError("responseSourceFactory must not be invoked for a non-http-header app, but was invoked for: " + u); });

        AppConfig app = new AppConfig(
                "regular-http-app", VersionScheme.SEMVER, Optional.empty(), Optional.empty(),
                "http", Optional.of("http://example.test/current"), Optional.of("/version"), false,
                Optional.empty(), Optional.empty(),
                "github-release", Optional.empty(), Optional.empty());

        ValidationOutcome.ConfigFileResult result = validation.validate(List.of(app), false);

        AppValidationResult appResult = result.apps().get(0);
        assertEquals(SurfaceResult.Status.NOT_APPLICABLE, surface(appResult, SurfaceResult.Surface.HEADER).status());
    }

    @Test
    void httpHeaderApp_missingHeaderNameConfig_isReportedAsAConfigError() {
        // 'version-header' is REQUIRED for this kind — slice 03's factory throws at boot on an
        // absent or blank one (ADR-0030). Reporting 'not applicable' would let conf-check pass a
        // config the backend then refuses to start on, defeating the point of a pre-deploy gate,
        // so this must be a config error. No fetch is attempted: the config is unusable as written.
        ConfigFileValidation validation = new ConfigFileValidation(
                BODY_NEVER_INVOKED,
                u -> { throw new AssertionError("responseSourceFactory must not be invoked when version-header is unset, but was invoked for: " + u); });

        AppConfig app = new AppConfig(
                "jenkins", VersionScheme.SEMVER, Optional.empty(), Optional.empty(),
                "http-header", Optional.of("http://example.test/"), Optional.empty(), false,
                Optional.empty(), Optional.empty(),
                "github-release", Optional.empty(), Optional.empty());

        ValidationOutcome.ConfigFileResult result = validation.validate(List.of(app), false);

        AppValidationResult appResult = result.apps().get(0);
        SurfaceResult headerSurface = surface(appResult, SurfaceResult.Surface.HEADER);
        assertEquals(SurfaceResult.Status.RAN, headerSurface.status());
        assertInstanceOf(ValidationOutcome.ConfigInvalid.class, headerSurface.outcome().orElseThrow(),
                "a required 'version-header' that is not configured is a config error, not a surface "
                        + "with nothing to check");
    }

    @Test
    void httpHeaderApp_missingUrl_isReportedAsAConfigError() {
        // Same reasoning as the missing 'version-header' case one field over: 'url' is required for
        // this kind and the backend's factory throws at boot without it, so the gate must fail the
        // app rather than wave it through as nothing to check.
        ConfigFileValidation validation = new ConfigFileValidation(
                BODY_NEVER_INVOKED,
                u -> { throw new AssertionError("responseSourceFactory must not be invoked when url is unset, but was invoked for: " + u); });

        AppConfig app = new AppConfig(
                "jenkins", VersionScheme.SEMVER, Optional.empty(), Optional.empty(),
                "http-header", Optional.empty(), Optional.empty(), false,
                Optional.of("X-Jenkins"), Optional.empty(),
                "github-release", Optional.empty(), Optional.empty());

        ValidationOutcome.ConfigFileResult result = validation.validate(List.of(app), false);

        SurfaceResult headerSurface = surface(result.apps().get(0), SurfaceResult.Surface.HEADER);
        assertEquals(SurfaceResult.Status.RAN, headerSurface.status());
        assertInstanceOf(ValidationOutcome.ConfigInvalid.class, headerSurface.outcome().orElseThrow(),
                "a required 'url' that is not configured is a config error");
    }

    @Test
    void httpHeaderApp_blankVersionHeader_isReportedAsAConfigError() {
        // The backend's factory rejects a blank 'version-header' with isBlank(), not merely an
        // absent one. A gate that only checked for absence would let "   " through to a fetch and
        // report a confusing "Header '   ' was absent" instead of the config error it is.
        ConfigFileValidation validation = new ConfigFileValidation(
                BODY_NEVER_INVOKED,
                u -> { throw new AssertionError("responseSourceFactory must not be invoked for a blank version-header, but was invoked for: " + u); });

        ValidationOutcome.ConfigFileResult result = validation.validate(
                List.of(httpHeaderApp("http://example.test/", "   ", Optional.empty())), false);

        SurfaceResult headerSurface = surface(result.apps().get(0), SurfaceResult.Surface.HEADER);
        assertEquals(SurfaceResult.Status.RAN, headerSurface.status());
        assertInstanceOf(ValidationOutcome.ConfigInvalid.class, headerSurface.outcome().orElseThrow(),
                "a blank 'version-header' is a config error, matching the factory's isBlank() rule");
    }

    private static AppConfig httpHeaderApp(String url, String headerName, Optional<String> headerRegex) {
        return new AppConfig(
                "jenkins", VersionScheme.SEMVER, Optional.empty(), Optional.empty(),
                "http-header", Optional.of(url), Optional.empty(), false,
                Optional.of(headerName), headerRegex,
                "github-release", Optional.empty(), Optional.empty());
    }

    private static ResponseSource.Response response(int statusCode, Map<String, List<String>> headers) {
        return new ResponseSource.Response(statusCode, headers);
    }

    /**
     * A fake response-source factory that returns canned responses for known URLs and fails the
     * test if asked for a URL it wasn't told about, mirroring
     * {@code ConfigFileValidationTests#fakeBodySource}.
     */
    private static Function<String, ResponseSource> fakeResponseSource(Map<String, ResponseSource.Response> okResponses) {
        return url -> {
            ResponseSource.Response response = okResponses.get(url);
            if (response == null) {
                throw new AssertionError("unexpected fetch for url: " + url);
            }
            return () -> response;
        };
    }

    private static SurfaceResult surface(AppValidationResult appResult, SurfaceResult.Surface surface) {
        return appResult.surfaces().stream()
                .filter(s -> s.surface() == surface)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no SurfaceResult for " + surface));
    }
}
