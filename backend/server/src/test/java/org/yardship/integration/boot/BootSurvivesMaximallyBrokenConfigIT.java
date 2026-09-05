package org.yardship.integration.boot;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.configerror.ConfigError;
import org.yardship.adapters.out.versionsource.configerror.ConfigErrorScope;
import org.yardship.adapters.out.versionsource.configerror.ConfigErrors;
import org.yardship.core.domain.primitives.VersionApplication;
import org.yardship.core.ports.in.ApplicationVersionPort;
import org.yardship.core.ports.in.Outcome;
import org.yardship.core.ports.in.ScrapeStatus;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The regression guard for ADR-0032 (issue 09 / issue #52): the rule "a config error degrades
 * only what it touches; it never fails the boot" used to live only as prose scattered across five
 * ADRs, and that prose drifted. This is the executable version of that rule.
 *
 * <p>{@link MaximallyBrokenConfigTestProfile} configures one FULLY VALID app plus one app per
 * config-error defect class ADR-0032 names (unknown/retired {@code type}, a blank {@code url}, a
 * capture-group-less {@code regex}, an unreadable {@code ca-cert}, two flavours of incoherent
 * {@code auth}, a {@code calver} scheme with no {@code calver-format}, an illegal
 * {@code changelog-url} placeholder) plus one app with no {@code name} at all.
 *
 * <p><b>Assertion 1 — the one that matters — is implicit and structural, not a line of code
 * inside the test method:</b> {@code @QuarkusTest} boots the ENTIRE application, against this
 * maximally broken fixture, before any {@code @Test} method here runs. If a factory, a startup
 * wiring bean, or a boot-time check nobody has written yet is reintroduced anywhere among these
 * nine defect classes, CDI container startup itself fails and every test in this class fails with
 * a container-startup exception — not a normal assertion failure — which is exactly the loud,
 * immediate signal ADR-0032 requires, and this class's name is the invariant that broke.
 */
@QuarkusTest
@TestProfile(MaximallyBrokenConfigTestProfile.class)
class BootSurvivesMaximallyBrokenConfigIT {

    private static WireMockServer wireMockServer;

    @Inject
    ApplicationVersionPort applicationVersionPort;

    @Inject
    ConfigErrors configErrors;

    @Inject
    RedisDataSource redisDataSource;

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(options().port(8089));
        wireMockServer.start();
        wireMockServer.stubFor(get(urlEqualTo("/healthy/current"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"version\":\"1.2.3\"}")));
        wireMockServer.stubFor(get(urlEqualTo("/healthy/latest"))
                .willReturn(aResponse().withStatus(200).withBody("current release: v1.4.0")));
        // /filler/current and /filler/latest are DELIBERATELY left unstubbed — see
        // MaximallyBrokenConfigTestProfile's class Javadoc.
    }

    @AfterAll
    static void stopWireMock() {
        wireMockServer.stop();
    }

    @BeforeEach
    void clearSharedScrapeState() {
        // These three keys are shared cluster-wide (here: across every @QuarkusTest sharing this
        // JVM's Dev Services Valkey container), so a leftover lock, spent budget, or a snapshot
        // written by a differently-configured test could make this test's single trigger flaky.
        redisDataSource.key().del("scrape:lock", "scrape:budget", "scrape:snapshot");
    }

    // Assertion 1 -- the one this issue exists for -- needs no method body: @QuarkusTest boots the
    // whole container against the broken fixture before any test below runs, so reintroducing a
    // boot-fatal config check ANYWHERE (a factory, a startup wiring bean, or one nobody has written
    // yet) fails this class at startup. The methods below are split one concern per test so that a
    // failure in the scrape does not hide the config-error set or the unnamed-app accounting.

    @Test
    void theValidAppStillScrapes_soTheBrokenSiblingsDidNotPoisonTheFleet() {
        ScrapeStatus status = applicationVersionPort.triggerScrape();
        assertEquals(Outcome.SCRAPED, status.outcome(), "the manual scrape must actually run");

        List<VersionApplication> applications = applicationVersionPort.getApplications();
        VersionApplication healthyApp = applications.stream()
                .filter(app -> app.name().equals("healthy-app"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("'healthy-app' is missing from the scraped fleet"));
        assertEquals("1.2.3", healthyApp.current().value().orElseThrow().value());
        assertEquals("1.4.0", healthyApp.latest().value().orElseThrow().value());
    }

    @Test
    void everyDefectIsReportedUnderExactlyTheExpectedScope() {
        // --- Assertion 3: the full (app, scope, reason) set, EXACTLY -- as a Set, not a List, so
        // this pins WHICH scope each defect is recorded under (and that it is recorded exactly
        // once) without also pinning cross-ConfigErrorSource discovery order, which CDI does not
        // guarantee. This is what stops a future author from quietly promoting a side-scope error
        // to app-scope, or duplicating an app-scope error across both CURRENT and LATEST. ---
        Set<ConfigError> expectedErrors = Set.of(
                new ConfigError("unknown-type-app", ConfigErrorScope.CURRENT,
                        "No version source factory for config type 'does-not-exist'."),
                new ConfigError("retired-type-app", ConfigErrorScope.CURRENT,
                        "The 'http' version source kind was renamed to 'http-json'; "
                                + "update this app's config."),
                new ConfigError("blank-url-app", ConfigErrorScope.CURRENT,
                        "The 'http-json' current source requires a non-blank 'url'."),
                new ConfigError("bad-regex-app", ConfigErrorScope.LATEST,
                        "The 'http-regex' latest source's 'regex' must have at least one capture "
                                + "group (group 1 is read); was: '\\d+'."),
                new ConfigError("bad-ca-cert-app", ConfigErrorScope.CURRENT,
                        "The 'http-json' current source's 'ca-cert' could not be read as X.509 PEM "
                                + "from '/nonexistent/path/does-not-exist.pem' "
                                + "(url: 'http://localhost:8089/filler/current'): "
                                + "/nonexistent/path/does-not-exist.pem"),
                new ConfigError("incoherent-auth-token-app", ConfigErrorScope.CURRENT,
                        "The 'http-json' current source's auth.type 'bearer' has both a token and a "
                                + "token-file; this is ambiguous and refused, no precedence rule "
                                + "(url: 'http://localhost:8089/filler/current')."),
                new ConfigError("incoherent-auth-basic-app", ConfigErrorScope.CURRENT,
                        "The 'http-json' current source's auth.type 'basic' is missing a username or "
                                + "password (url: 'http://localhost:8089/filler/current')."),
                new ConfigError("calver-no-format-app", ConfigErrorScope.APP,
                        "Invalid version-scheme configuration for app 'calver-no-format-app': "
                                + "CalverFormat: format string must not be null or blank"),
                new ConfigError("uncompilable-regex-app", ConfigErrorScope.LATEST,
                        "The 'http-regex' latest source's 'regex' does not compile: "
                                + "Unclosed group near index 5\nv(\\d+"),
                new ConfigError("illegal-changelog-app", ConfigErrorScope.CHANGELOG,
                        "Invalid 'changelog-url' template for app 'illegal-changelog-app': "
                                + "Changelog template placeholder '{bogus}' is not a legal token for "
                                + "a SEMVER app "
                                + "(template: 'https://example.test/changes/{bogus}')."));

        assertEquals(expectedErrors, Set.copyOf(configErrors.all()),
                "the recorded (app, scope, reason) set must match exactly");
        // Set.copyOf collapses duplicates silently, so the set comparison alone would not notice
        // the SAME (app, scope, reason) being recorded twice. Size pins the once-only rule.
        assertEquals(expectedErrors.size(), configErrors.all().size(),
                "no config error may be recorded twice: " + configErrors.all());
    }

    @Test
    void onlyTheUnnamedAppIsAbsentFromTheFleet_andItIsCountedInstead() {
        // --- Assertion 4: the unnamed app is absent from the fleet on every Surface, and counted
        // in the unlabelled pu2d_config_unnamed_apps metric. ---
        assertEquals(1, configErrors.unnamedAppCount(),
                "exactly one configured app has no 'name' and must be counted, not reported");

        // Per CONTEXT.md's "Config error" / "Failed scrape" terms: "one Application's config
        // error never stops another from being monitored" and "an Application with no name is
        // not monitored at all" -- the UNNAMED app is the only one that may be absent here. Every
        // other app, including an APP-scope-broken one, is still "monitored" and must register as
        // a Failed scrape carrying its error's reason, not vanish.
        //
        // This guard caught a real defect on its first run: ValkeyScrapeStateStore used to skip ANY
        // app whose VersionParsers.forApp(name) was empty when reading the snapshot back, which
        // cannot tell "removed from config" apart from "still configured, but its version-scheme
        // has an APP-scope config error" -- silently dropping the latter from EVERY Surface, since
        // REST, MCP and metrics all read through ApplicationVersionPort. It now gates on whether
        // the app is still CONFIGURED, so an APP-scope-broken app stays in the fleet, unresolved,
        // with its reason carried by the configErrors projection. Keep this assertion at 10.
        List<VersionApplication> applications = applicationVersionPort.getApplications();
        Set<String> fleetAppNames =
                applications.stream().map(VersionApplication::name).collect(Collectors.toSet());
        assertEquals(11, fleetAppNames.size(),
                "only the eleven NAMED apps may reach the core fleet (ApplicationVersionPort)");

        // REST Surface: same omission, through the real HTTP endpoint every replica serves.
        given()
                .when().get("/api/v1/version")
                .then()
                .statusCode(200)
                .body("size()", equalTo(11))
                .body("'healthy-app'.current.version", equalTo("1.2.3"));

        // Metrics Surface: the unnamed app has no identity to label a series with, so it is never
        // an app= label anywhere -- it is only this one unlabelled gauge.
        String metrics = given().when().get("/metrics").then().statusCode(200)
                .extract().asString();
        // Whole-line match: a bare contains() would also accept "pu2d_config_unnamed_apps 10".
        assertTrue(metrics.lines().anyMatch(line -> line.equals("pu2d_config_unnamed_apps 1")),
                "the unnamed-app count must be exposed on the metrics surface as exactly 1: " + metrics);
    }
}
