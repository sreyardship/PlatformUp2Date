package org.yardship.unit.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@code config} gate's NEW {@code http-prometheus} surface (ADR-0033, slice 04
 * of {@code .scratch/http-prometheus-current-source}) — deliberately a separate file from
 * {@code ConfigFileValidationTests}/{@code ConfigFileValidationHeaderSurfaceTests}, following the
 * precedent those two files already set for keeping each kind's surface tests isolated.
 *
 * <p>Unlike the {@code header} surface, this one never fetches anything: per the issue, extraction
 * against a real Prometheus body is slice 05's separate command, so both the body-source and
 * response-source factories passed to {@link ConfigFileValidation} here must NEVER be invoked —
 * every assertion below is enforced by throwing from those factories if the surface ever tries to
 * fetch.
 */
class ConfigFileValidationPrometheusSurfaceTests {

    /** Both fetch seams fail the test immediately if the prometheus surface ever invokes them. */
    private static final Function<String, BodySource> BODY_NEVER_INVOKED =
            url -> { throw new AssertionError("bodySourceFactory must not be invoked, but was invoked for url: " + url); };
    private static final Function<String, ResponseSource> RESPONSE_NEVER_INVOKED =
            url -> { throw new AssertionError("responseSourceFactory must not be invoked, but was invoked for url: " + url); };

    @Test
    void httpPrometheusApp_urlAndMetricPresent_passes() {
        ConfigFileValidation validation = new ConfigFileValidation(BODY_NEVER_INVOKED, RESPONSE_NEVER_INVOKED);

        AppConfig app = prometheusApp("http://example.test/metrics", "build_info", Optional.empty(), Optional.empty());

        ValidationOutcome.ConfigFileResult result = validation.validate(List.of(app), false);

        AppValidationResult appResult = result.apps().get(0);
        assertFalse(appResult.isFailure());
        SurfaceResult prometheus = surface(appResult, SurfaceResult.Surface.PROMETHEUS);
        assertEquals(SurfaceResult.Status.RAN, prometheus.status());
        assertInstanceOf(ValidationOutcome.PrometheusConfigValid.class, prometheus.outcome().orElseThrow());
        assertEquals(ValidationOutcome.ConfigFileResult.ALL_OK_EXIT_CODE, result.exitCode());
    }

    @Test
    void httpPrometheusApp_missingMetric_isReportedAsAConfigError() {
        // 'metric' is REQUIRED for this kind: HttpPrometheusCurrentSourceFactory throws at boot
        // (degrading that one app, ADR-0032) on an absent/blank metric. conf-check must refuse the
        // same config, with wording consistent with the sibling kinds' required-field messages
        // (see ConfigFileValidation#missingRequiredHeaderField's identical "requires a non-blank
        // '<field>'" phrasing).
        ConfigFileValidation validation = new ConfigFileValidation(BODY_NEVER_INVOKED, RESPONSE_NEVER_INVOKED);

        AppConfig app = prometheusApp("http://example.test/metrics", null, Optional.empty(), Optional.empty());

        ValidationOutcome.ConfigFileResult result = validation.validate(List.of(app), false);

        SurfaceResult prometheus = surface(result.apps().get(0), SurfaceResult.Surface.PROMETHEUS);
        assertEquals(SurfaceResult.Status.RAN, prometheus.status());
        ValidationOutcome outcome = prometheus.outcome().orElseThrow();
        assertInstanceOf(ValidationOutcome.ConfigInvalid.class, outcome);
        assertTrue(((ValidationOutcome.ConfigInvalid) outcome).message().contains("non-blank 'metric'"),
                "message should name 'metric' consistently with the sibling kinds' required-field "
                        + "wording; was: " + ((ValidationOutcome.ConfigInvalid) outcome).message());
        assertEquals(ValidationOutcome.ConfigFileResult.SOME_FAILED_EXIT_CODE, result.exitCode());
    }

    @Test
    void httpPrometheusApp_blankMetric_isReportedAsAConfigError() {
        // The backend's factory rejects a blank 'metric' with isBlank(), not merely an absent one.
        ConfigFileValidation validation = new ConfigFileValidation(BODY_NEVER_INVOKED, RESPONSE_NEVER_INVOKED);

        AppConfig app = prometheusApp("http://example.test/metrics", "   ", Optional.empty(), Optional.empty());

        ValidationOutcome.ConfigFileResult result = validation.validate(List.of(app), false);

        SurfaceResult prometheus = surface(result.apps().get(0), SurfaceResult.Surface.PROMETHEUS);
        assertInstanceOf(ValidationOutcome.ConfigInvalid.class, prometheus.outcome().orElseThrow(),
                "a blank 'metric' is a config error, matching the factory's isBlank() rule");
    }

    @Test
    void httpPrometheusApp_missingUrl_isReportedAsAConfigError() {
        ConfigFileValidation validation = new ConfigFileValidation(BODY_NEVER_INVOKED, RESPONSE_NEVER_INVOKED);

        AppConfig app = prometheusApp(null, "build_info", Optional.empty(), Optional.empty());

        ValidationOutcome.ConfigFileResult result = validation.validate(List.of(app), false);

        SurfaceResult prometheus = surface(result.apps().get(0), SurfaceResult.Surface.PROMETHEUS);
        ValidationOutcome outcome = prometheus.outcome().orElseThrow();
        assertInstanceOf(ValidationOutcome.ConfigInvalid.class, outcome);
        assertTrue(((ValidationOutcome.ConfigInvalid) outcome).message().contains("non-blank 'url'"),
                "message should name 'url' consistently with the sibling kinds' required-field "
                        + "wording; was: " + ((ValidationOutcome.ConfigInvalid) outcome).message());
    }

    @Test
    void httpPrometheusApp_blankUrl_isReportedAsAConfigError() {
        ConfigFileValidation validation = new ConfigFileValidation(BODY_NEVER_INVOKED, RESPONSE_NEVER_INVOKED);

        AppConfig app = prometheusApp("   ", "build_info", Optional.empty(), Optional.empty());

        ValidationOutcome.ConfigFileResult result = validation.validate(List.of(app), false);

        SurfaceResult prometheus = surface(result.apps().get(0), SurfaceResult.Surface.PROMETHEUS);
        ValidationOutcome outcome = prometheus.outcome().orElseThrow();

        assertInstanceOf(ValidationOutcome.ConfigInvalid.class, outcome);
        // The parity table already covers the accept/reject verdict for a blank url; what it does
        // not cover, and this does, is that a BLANK url is reported with the same wording as an
        // ABSENT one rather than some second phrasing an operator has to learn.
        assertTrue(((ValidationOutcome.ConfigInvalid) outcome).message().contains("non-blank 'url'"),
                "a blank url must be reported with the same wording as an absent one; was: "
                        + ((ValidationOutcome.ConfigInvalid) outcome).message());
    }

    @Test
    void httpPrometheusApp_versionLabelAbsent_isNotAnError() {
        // 'version-label' defaults factory-side to "version" (HttpPrometheusCurrentSourceFactory);
        // conf-check must accept an app that omits it, exactly as the backend does.
        ConfigFileValidation validation = new ConfigFileValidation(BODY_NEVER_INVOKED, RESPONSE_NEVER_INVOKED);

        AppConfig app = prometheusApp("http://example.test/metrics", "build_info", Optional.empty(), Optional.empty());
        assertTrue(app.currentVersionLabel().isEmpty(), "test setup: version-label must be absent");

        ValidationOutcome.ConfigFileResult result = validation.validate(List.of(app), false);

        assertFalse(result.apps().get(0).isFailure(),
                "an absent 'version-label' must never fail the gate — it defaults, as the backend defaults it");
    }

    @Test
    void httpPrometheusApp_regexDoesNotCompile_isReportedAsAConfigError() {
        ConfigFileValidation validation = new ConfigFileValidation(BODY_NEVER_INVOKED, RESPONSE_NEVER_INVOKED);

        AppConfig app = prometheusApp(
                "http://example.test/metrics", "build_info", Optional.empty(), Optional.of("(unterminated"));

        ValidationOutcome.ConfigFileResult result = validation.validate(List.of(app), false);

        SurfaceResult prometheus = surface(result.apps().get(0), SurfaceResult.Surface.PROMETHEUS);
        assertInstanceOf(ValidationOutcome.ConfigInvalid.class, prometheus.outcome().orElseThrow(),
                "a regex that fails to compile must be reported as a config error");
    }

    @Test
    void httpPrometheusApp_regexHasNoCaptureGroup_isReportedAsAConfigError() {
        ConfigFileValidation validation = new ConfigFileValidation(BODY_NEVER_INVOKED, RESPONSE_NEVER_INVOKED);

        AppConfig app = prometheusApp(
                "http://example.test/metrics", "build_info", Optional.empty(), Optional.of("v\\d+\\.\\d+\\.\\d+"));

        ValidationOutcome.ConfigFileResult result = validation.validate(List.of(app), false);

        SurfaceResult prometheus = surface(result.apps().get(0), SurfaceResult.Surface.PROMETHEUS);
        assertInstanceOf(ValidationOutcome.ConfigInvalid.class, prometheus.outcome().orElseThrow(),
                "a regex with no capture group 1 must be reported as a config error");
    }

    @Test
    void httpPrometheusApp_validRegex_passes() {
        ConfigFileValidation validation = new ConfigFileValidation(BODY_NEVER_INVOKED, RESPONSE_NEVER_INVOKED);

        AppConfig app = prometheusApp(
                "http://example.test/metrics", "build_info", Optional.of("version"),
                Optional.of("v(\\d+\\.\\d+\\.\\d+)"));

        ValidationOutcome.ConfigFileResult result = validation.validate(List.of(app), false);

        assertFalse(result.apps().get(0).isFailure());
    }

    @Test
    void nonHttpPrometheusApp_prometheusSurfaceIsNotApplicable() {
        ConfigFileValidation validation = new ConfigFileValidation(
                url -> () -> "irrelevant-body", RESPONSE_NEVER_INVOKED);

        AppConfig app = new AppConfig(
                "regular-http-json-app", VersionScheme.SEMVER, Optional.empty(), Optional.empty(),
                "http-json", Optional.of("http://example.test/current"), Optional.of("/version"), false,
                Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Map.of(),
                "github-release", Optional.empty(), Optional.empty());

        ValidationOutcome.ConfigFileResult result = validation.validate(List.of(app), false);

        assertEquals(SurfaceResult.Status.NOT_APPLICABLE,
                surface(result.apps().get(0), SurfaceResult.Surface.PROMETHEUS).status());
    }

    @Test
    void offline_doesNotSkipOrFailThePrometheusSurface() {
        // Pure config check, no network -- like changelog/calver, --offline has no effect on it.
        ConfigFileValidation validation = new ConfigFileValidation(BODY_NEVER_INVOKED, RESPONSE_NEVER_INVOKED);

        AppConfig app = prometheusApp("http://example.test/metrics", "build_info", Optional.empty(), Optional.empty());

        ValidationOutcome.ConfigFileResult result = validation.validate(List.of(app), true);

        SurfaceResult prometheus = surface(result.apps().get(0), SurfaceResult.Surface.PROMETHEUS);
        assertEquals(SurfaceResult.Status.RAN, prometheus.status());
        assertFalse(result.apps().get(0).isFailure());
    }

    /**
     * THE parity acceptance criterion: the gate's verdict for an {@code http-prometheus} app must
     * match what the backend actually does with the same config — no case where one accepts and
     * the other degrades (ADR-0031). {@code :backend:conf-check} cannot depend on
     * {@code :backend:server} (see {@code AppConfig}'s javadoc), so this cannot call
     * {@code HttpPrometheusCurrentSourceFactory} directly; instead it pins, case by case, the exact
     * accept/reject rule documented on that factory (non-blank {@code url}, non-blank
     * {@code metric}, both required; everything else optional) against every combination of
     * absent/blank/present for the two required fields.
     *
     * <p>Be honest about what this buys: the table is a PINNED TRANSCRIPTION of the backend's
     * rule, not a reading of it. A change to the GATE's rule breaks this test; a change to the
     * BACKEND's rule does not — the table would keep passing against a stale expectation. The
     * module boundary leaves no better option for the required-field half, so it must be updated
     * by hand alongside {@code HttpPrometheusCurrentSourceFactory}. The regex half needs no such
     * pinning: both sides construct the same {@code VersionPattern} from {@code :backend:domain}
     * and so cannot drift.
     */
    @ParameterizedTest(name = "url={0}, metric={1} -> backend accepts={2}")
    @MethodSource("urlMetricCombinations")
    void gateVerdict_matchesBackendFactoryRule_forEveryUrlMetricCombination(
            String url, String metric, boolean backendWouldAccept) {
        ConfigFileValidation validation = new ConfigFileValidation(BODY_NEVER_INVOKED, RESPONSE_NEVER_INVOKED);

        AppConfig app = prometheusApp(url, metric, Optional.empty(), Optional.empty());

        ValidationOutcome.ConfigFileResult result = validation.validate(List.of(app), false);

        boolean gateAccepts = !result.apps().get(0).isFailure();
        assertEquals(backendWouldAccept, gateAccepts,
                "gate verdict disagreed with the documented backend rule for url='" + url
                        + "', metric='" + metric + "'");
    }

    private static Stream<Arguments> urlMetricCombinations() {
        return Stream.of(
                // HttpPrometheusCurrentSourceFactory.requireNonBlank() accepts iff both a
                // non-blank url and a non-blank metric are present -- everything else degrades.
                Arguments.of("http://example.test/metrics", "build_info", true),
                Arguments.of(null, "build_info", false),
                Arguments.of("   ", "build_info", false),
                Arguments.of("http://example.test/metrics", null, false),
                Arguments.of("http://example.test/metrics", "   ", false),
                Arguments.of(null, null, false),
                Arguments.of("   ", "   ", false));
    }

    private static AppConfig prometheusApp(
            String url, String metric, Optional<String> versionLabel, Optional<String> regex) {
        return new AppConfig(
                "prometheus-app", VersionScheme.SEMVER, Optional.empty(), Optional.empty(),
                "http-prometheus", Optional.ofNullable(url), Optional.empty(), false,
                Optional.empty(), regex,
                Optional.ofNullable(metric), versionLabel, Map.of(),
                "github-release", Optional.empty(), Optional.empty());
    }

    private static SurfaceResult surface(AppValidationResult appResult, SurfaceResult.Surface surface) {
        return appResult.surfaces().stream()
                .filter(s -> s.surface() == surface)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no SurfaceResult for " + surface));
    }
}
