package org.yardship.integration.boot;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.HashMap;
import java.util.Map;

/**
 * The fixture for {@link BootSurvivesMaximallyBrokenConfigIT} (issue 09 / ADR-0032): one fully
 * valid app plus one app per config-error defect class the ADR names, plus one app with no
 * {@code name} at all. Every property here is a {@code platform-config} override exactly as an
 * operator would write it in the ConfigMap — nothing here is a test-only shortcut.
 *
 * <p>Every "other", unaffected leg of a defect app points at a shared WireMock filler endpoint
 * ({@code /filler/current} or {@code /filler/latest}) that is deliberately left UNSTUBBED: it is
 * structurally legal config (so it records no {@link
 * org.yardship.adapters.out.versionsource.configerror.ConfigError} of its own), and a 404 at
 * SCRAPE time is harmless here — config errors are recorded only at STARTUP resolution, never
 * from a later scrape failure (ADR-0032) — so the fixture needs no stub for it.
 */
public class MaximallyBrokenConfigTestProfile implements QuarkusTestProfile {

    private static final String WIREMOCK_BASE = "http://localhost:8089";
    private static final String FILLER_CURRENT_URL = WIREMOCK_BASE + "/filler/current";
    private static final String FILLER_LATEST_URL = WIREMOCK_BASE + "/filler/latest";
    private static final String FILLER_REGEX = "v(\\d+\\.\\d+\\.\\d+)";

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> props = new HashMap<>();
        props.put("quarkus.scheduler.enabled", "false");
        props.put("platform-config.scrape-interval", "1h");
        // Generous budget: this test spends exactly one manual-trigger slot, but the Valkey-backed
        // budget ZSET is shared cluster-wide (here: across every @QuarkusTest sharing this JVM's
        // Dev Services Valkey container), so the tight production default could flake if this test
        // happens to run after others have already spent it. The test also clears the key itself
        // in @BeforeEach as the primary guard; this is a belt-and-braces second one.
        props.put("platform-config.scrape-trigger.max-per-window", "1000");

        // apps[0]: the one FULLY VALID app -- proves the broken siblings below do not poison it.
        props.put(app(0, "name"), "healthy-app");
        props.put(app(0, "current.type"), "http-json");
        props.put(app(0, "current.url"), WIREMOCK_BASE + "/healthy/current");
        props.put(app(0, "latest.type"), "http-regex");
        props.put(app(0, "latest.url"), WIREMOCK_BASE + "/healthy/latest");
        props.put(app(0, "latest.regex"), FILLER_REGEX);

        // apps[1]: unknown 'type' -- no factory exists for this kind at all.
        props.put(app(1, "name"), "unknown-type-app");
        props.put(app(1, "current.type"), "does-not-exist");
        fillerLatest(props, 1);

        // apps[2]: retired 'type' -- 'http' was renamed to 'http-json'.
        props.put(app(2, "name"), "retired-type-app");
        props.put(app(2, "current.type"), "http");
        fillerLatest(props, 2);

        // apps[3]: 'url' present but blank.
        props.put(app(3, "name"), "blank-url-app");
        props.put(app(3, "current.type"), "http-json");
        props.put(app(3, "current.url"), "");
        fillerLatest(props, 3);

        // apps[4]: 'regex' compiles but declares no capture group 1 to read.
        props.put(app(4, "name"), "bad-regex-app");
        fillerCurrent(props, 4);
        props.put(app(4, "latest.type"), "http-regex");
        props.put(app(4, "latest.url"), FILLER_LATEST_URL);
        props.put(app(4, "latest.regex"), "\\d+");

        // apps[13]: 'regex' does not compile at all -- a distinct production branch from apps[4]
        // (RegexVersionExtractor.compile's PatternSyntaxException arm, not its capture-group arm).
        // Its reason embeds the JDK's own PatternSyntaxException text, which is the one expected
        // message in this fixture coupled to the JDK rather than to our code; it has been stable
        // for many releases, and covering the branch is worth that coupling.
        props.put(app(13, "name"), "uncompilable-regex-app");
        fillerCurrent(props, 13);
        props.put(app(13, "latest.type"), "http-regex");
        props.put(app(13, "latest.url"), FILLER_LATEST_URL);
        props.put(app(13, "latest.regex"), "v(\\d+");

        // apps[5]: 'ca-cert' names a path that cannot be read.
        props.put(app(5, "name"), "bad-ca-cert-app");
        props.put(app(5, "current.type"), "http-json");
        props.put(app(5, "current.url"), FILLER_CURRENT_URL);
        props.put(app(5, "current.ca-cert"), "/nonexistent/path/does-not-exist.pem");
        fillerLatest(props, 5);

        // apps[6]: incoherent 'auth' -- both a token and a token-file, no precedence rule.
        props.put(app(6, "name"), "incoherent-auth-token-app");
        props.put(app(6, "current.type"), "http-json");
        props.put(app(6, "current.url"), FILLER_CURRENT_URL);
        props.put(app(6, "current.auth.type"), "bearer");
        props.put(app(6, "current.auth.token"), "some-token");
        props.put(app(6, "current.auth.token-file"), "/some/path/token");
        fillerLatest(props, 6);

        // apps[7]: incoherent 'auth' -- 'basic' with no username/password at all.
        props.put(app(7, "name"), "incoherent-auth-basic-app");
        props.put(app(7, "current.type"), "http-json");
        props.put(app(7, "current.url"), FILLER_CURRENT_URL);
        props.put(app(7, "current.auth.type"), "basic");
        fillerLatest(props, 7);

        // apps[8]: 'version-scheme: calver' with no 'calver-format' at all.
        props.put(app(8, "name"), "calver-no-format-app");
        props.put(app(8, "version-scheme"), "calver");
        fillerCurrent(props, 8);
        fillerLatest(props, 8);

        // apps[9]: illegal 'changelog-url' template -- an unknown placeholder for a SEMVER app.
        props.put(app(9, "name"), "illegal-changelog-app");
        props.put(app(9, "changelog-url"), "https://example.test/changes/{bogus}");
        fillerCurrent(props, 9);
        fillerLatest(props, 9);

        // apps[10]: NO 'name' AT ALL -- the un-bindable-identity case (issue 02 / ADR-0032).
        // Dropped from the fleet entirely; never a ConfigError entry, since there is no identity
        // to record one under.
        fillerCurrent(props, 10);
        fillerLatest(props, 10);

        return props;
    }

    private static void fillerCurrent(Map<String, String> props, int index) {
        props.put(app(index, "current.type"), "http-json");
        props.put(app(index, "current.url"), FILLER_CURRENT_URL);
    }

    private static void fillerLatest(Map<String, String> props, int index) {
        props.put(app(index, "latest.type"), "http-regex");
        props.put(app(index, "latest.url"), FILLER_LATEST_URL);
        props.put(app(index, "latest.regex"), FILLER_REGEX);
    }

    private static String app(int index, String field) {
        return "platform-config.apps[" + index + "]." + field;
    }
}
