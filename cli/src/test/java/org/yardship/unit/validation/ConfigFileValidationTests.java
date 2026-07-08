package org.yardship.unit.validation;

import org.junit.jupiter.api.Test;
import org.yardship.cli.outcome.AppValidationResult;
import org.yardship.cli.outcome.SurfaceResult;
import org.yardship.cli.outcome.ValidationOutcome;
import org.yardship.cli.port.AppConfig;
import org.yardship.cli.port.BodySource;
import org.yardship.cli.validation.ConfigFileValidation;
import org.yardship.core.domain.primitives.VersionScheme;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fast unit tests for {@link ConfigFileValidation}, the {@code config} gate's composing use case
 * (issue 06). {@link ConfigFileValidation} depends only on the {@link BodySource} port (via an
 * injected {@code Function<String, BodySource>} factory) rather than any concrete HTTP adapter, so
 * these tests exercise the composing/branching logic under test (applicability, offline-skipping,
 * aggregate exit code) against fake {@link BodySource}s — no real HTTP server, no WireMock, no
 * network or port binding of any kind.
 */
class ConfigFileValidationTests {

    /** A body-source factory that fails the test immediately if it is ever invoked. */
    private static final Function<String, BodySource> NEVER_INVOKED =
            url -> { throw new AssertionError("bodySourceFactory must not be invoked, but was invoked for url: " + url); };

    @Test
    void regexOnlyApp_regexSurfaceRunsAndPasses_othersNotApplicable() {
        String url = "http://example.test/latest";
        ConfigFileValidation validation = new ConfigFileValidation(fakeBodySource(Map.of(url, "v1.2.3")));

        AppConfig app = appConfig(
                "regex-only", VersionScheme.SEMVER, Optional.empty(), Optional.empty(),
                "github-release", Optional.empty(), Optional.empty(), false,
                "http-regex", Optional.of(url), Optional.of("v(\\d+\\.\\d+\\.\\d+)"));

        ValidationOutcome.ConfigFileResult result = validation.validate(List.of(app), false);

        AppValidationResult appResult = result.apps().get(0);
        assertFalse(appResult.isFailure());
        assertRan(appResult, SurfaceResult.Surface.REGEX);
        assertNotApplicable(appResult, SurfaceResult.Surface.POINTER);
        assertNotApplicable(appResult, SurfaceResult.Surface.CHANGELOG);
        assertNotApplicable(appResult, SurfaceResult.Surface.CALVER);
        assertEquals(ValidationOutcome.ConfigFileResult.ALL_OK_EXIT_CODE, result.exitCode());
    }

    @Test
    void pointerOnlyApp_pointerSurfaceRunsAndPasses() {
        String url = "http://example.test/current";
        ConfigFileValidation validation =
                new ConfigFileValidation(fakeBodySource(Map.of(url, "{\"version\":\"1.2.3\"}")));

        AppConfig app = appConfig(
                "pointer-only", VersionScheme.SEMVER, Optional.empty(), Optional.empty(),
                "http", Optional.of(url), Optional.of("/version"), false,
                "github-release", Optional.empty(), Optional.empty());

        ValidationOutcome.ConfigFileResult result = validation.validate(List.of(app), false);

        AppValidationResult appResult = result.apps().get(0);
        assertFalse(appResult.isFailure());
        assertRan(appResult, SurfaceResult.Surface.POINTER);
        assertNotApplicable(appResult, SurfaceResult.Surface.REGEX);
    }

    @Test
    void changelogOnlyApp_changelogSurfaceRuns_bodySourceFactoryNeverInvoked() {
        ConfigFileValidation validation = new ConfigFileValidation(NEVER_INVOKED);

        AppConfig app = appConfig(
                "changelog-only", VersionScheme.SEMVER, Optional.empty(),
                Optional.of("https://example.test/releases/{version}"),
                "github-release", Optional.empty(), Optional.empty(), false,
                "github-release", Optional.empty(), Optional.empty());

        ValidationOutcome.ConfigFileResult result = validation.validate(List.of(app), false);

        AppValidationResult appResult = result.apps().get(0);
        assertFalse(appResult.isFailure());
        SurfaceResult changelog = surface(appResult, SurfaceResult.Surface.CHANGELOG);
        assertEquals(SurfaceResult.Status.RAN, changelog.status());
        assertTrue(changelog.outcome().orElseThrow() instanceof ValidationOutcome.ChangelogTemplateValid);
    }

    @Test
    void calverApp_calverSurfaceRuns_bodySourceFactoryNeverInvoked() {
        ConfigFileValidation validation = new ConfigFileValidation(NEVER_INVOKED);

        AppConfig app = appConfig(
                "calver-only", VersionScheme.CALVER, Optional.of("YY.0M.MICRO"), Optional.empty(),
                "github-release", Optional.empty(), Optional.empty(), false,
                "github-release", Optional.empty(), Optional.empty());

        ValidationOutcome.ConfigFileResult result = validation.validate(List.of(app), false);

        AppValidationResult appResult = result.apps().get(0);
        assertFalse(appResult.isFailure());
        SurfaceResult calver = surface(appResult, SurfaceResult.Surface.CALVER);
        assertEquals(SurfaceResult.Status.RAN, calver.status());
        assertTrue(calver.outcome().orElseThrow() instanceof ValidationOutcome.CalverFormatValid);
    }

    @Test
    void offline_skipsRegexAndPointer_bodySourceFactoryNeverInvoked_butChangelogAndCalverStillRun() {
        ConfigFileValidation validation = new ConfigFileValidation(NEVER_INVOKED);

        AppConfig app = appConfig(
                "full-app", VersionScheme.CALVER, Optional.of("YY.0M.MICRO"),
                Optional.of("https://example.test/releases/{YY}"),
                "http", Optional.of("http://example.test/current"), Optional.of("/version"), false,
                "http-regex", Optional.of("http://example.test/latest"), Optional.of("v(\\d+\\.\\d+)"));

        ValidationOutcome.ConfigFileResult result = validation.validate(List.of(app), true);

        AppValidationResult appResult = result.apps().get(0);
        assertEquals(SurfaceResult.Status.SKIPPED_OFFLINE, surface(appResult, SurfaceResult.Surface.REGEX).status());
        assertEquals(SurfaceResult.Status.SKIPPED_OFFLINE, surface(appResult, SurfaceResult.Surface.POINTER).status());
        assertEquals(SurfaceResult.Status.RAN, surface(appResult, SurfaceResult.Surface.CHANGELOG).status());
        assertEquals(SurfaceResult.Status.RAN, surface(appResult, SurfaceResult.Surface.CALVER).status());
        assertFalse(appResult.isFailure());
    }

    @Test
    void failingSurface_makesAppAndAggregateFail() {
        String failingUrl = "http://example.test/latest";
        String okUrl = "http://example.test/ok-latest";
        ConfigFileValidation validation = new ConfigFileValidation(
                fakeBodySource(Map.of(okUrl, "v1.0.0"), Set.of(failingUrl)));

        AppConfig failingApp = appConfig(
                "failing-app", VersionScheme.SEMVER, Optional.empty(), Optional.empty(),
                "github-release", Optional.empty(), Optional.empty(), false,
                "http-regex", Optional.of(failingUrl), Optional.of("v(\\d+\\.\\d+\\.\\d+)"));

        AppConfig passingApp = appConfig(
                "passing-app", VersionScheme.SEMVER, Optional.empty(), Optional.empty(),
                "github-release", Optional.empty(), Optional.empty(), false,
                "http-regex", Optional.of(okUrl), Optional.of("v(\\d+\\.\\d+\\.\\d+)"));

        ValidationOutcome.ConfigFileResult result = validation.validate(List.of(failingApp, passingApp), false);

        AppValidationResult failingResult = result.apps().get(0);
        assertTrue(failingResult.isFailure());
        SurfaceResult regex = surface(failingResult, SurfaceResult.Surface.REGEX);
        assertEquals(SurfaceResult.Status.RAN, regex.status());
        assertTrue(regex.outcome().orElseThrow() instanceof ValidationOutcome.FetchFailed);

        AppValidationResult passingResult = result.apps().get(1);
        assertFalse(passingResult.isFailure());

        assertEquals(ValidationOutcome.ConfigFileResult.SOME_FAILED_EXIT_CODE, result.exitCode());
    }

    @Test
    void appWithNothingApplicable_allFourSurfacesNotApplicable_bodySourceFactoryNeverInvoked() {
        ConfigFileValidation validation = new ConfigFileValidation(NEVER_INVOKED);

        AppConfig app = appConfig(
                "nothing-applicable", VersionScheme.SEMVER, Optional.empty(), Optional.empty(),
                "ssh-os-release", Optional.empty(), Optional.empty(), false,
                "github-release", Optional.empty(), Optional.empty());

        ValidationOutcome.ConfigFileResult result = validation.validate(List.of(app), false);

        AppValidationResult appResult = result.apps().get(0);
        assertFalse(appResult.isFailure());
        for (SurfaceResult.Surface s : SurfaceResult.Surface.values()) {
            assertEquals(SurfaceResult.Status.NOT_APPLICABLE, surface(appResult, s).status(),
                    "surface " + s + " must be NOT_APPLICABLE when nothing is configured for it");
        }
        assertEquals(ValidationOutcome.ConfigFileResult.ALL_OK_EXIT_CODE, result.exitCode());
    }

    /**
     * A fake body-source factory that returns canned bodies for known URLs and fails the test if
     * asked for a URL it wasn't told about (a stronger guarantee than silently returning an empty
     * body, which would mask a bug where the code under test fetches an unexpected URL).
     */
    private static Function<String, BodySource> fakeBodySource(Map<String, String> okBodies) {
        return fakeBodySource(okBodies, Set.of());
    }

    /**
     * As {@link #fakeBodySource(Map)}, but URLs in {@code failingUrls} produce a {@link BodySource}
     * whose {@code body()} throws {@link BodySource.BodyFetchException}, simulating a live-fetch
     * failure (network error / non-2xx response) without any real HTTP call.
     */
    private static Function<String, BodySource> fakeBodySource(Map<String, String> okBodies, Set<String> failingUrls) {
        return url -> {
            if (failingUrls.contains(url)) {
                return () -> {
                    throw new BodySource.BodyFetchException("simulated fetch failure for " + url);
                };
            }
            String body = okBodies.get(url);
            if (body == null) {
                throw new AssertionError("unexpected fetch for url: " + url);
            }
            return () -> body;
        };
    }

    private static SurfaceResult surface(AppValidationResult appResult, SurfaceResult.Surface surface) {
        return appResult.surfaces().stream()
                .filter(s -> s.surface() == surface)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no SurfaceResult for " + surface));
    }

    private static void assertRan(AppValidationResult appResult, SurfaceResult.Surface surface) {
        assertEquals(SurfaceResult.Status.RAN, surface(appResult, surface).status());
    }

    private static void assertNotApplicable(AppValidationResult appResult, SurfaceResult.Surface surface) {
        assertEquals(SurfaceResult.Status.NOT_APPLICABLE, surface(appResult, surface).status());
    }

    private static AppConfig appConfig(
            String name, VersionScheme versionScheme, Optional<String> calverFormat, Optional<String> changelogUrl,
            String currentType, Optional<String> currentUrl, Optional<String> currentVersionKey, boolean stripPrerelease,
            String latestType, Optional<String> latestUrl, Optional<String> latestRegex) {
        return new AppConfig(name, versionScheme, calverFormat, changelogUrl, currentType, currentUrl,
                currentVersionKey, stripPrerelease, latestType, latestUrl, latestRegex);
    }
}
