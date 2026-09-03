package org.yardship.integration.adapters.out.scrapestate.valkey;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.HashMap;
import java.util.Map;

/**
 * Adds one app whose {@code version-scheme} is {@code calver} with NO {@code calver-format}, so
 * {@code VersionParsers} records an APP-scope config error for it and builds no parser, while
 * {@code VersionSourceResolver} still registers it as a configured app (issue 03 / ADR-0032).
 *
 * <p>That combination — configured, but with no usable parser — is the one
 * {@code ValkeyScrapeStateStore} must NOT confuse with "removed from config".
 */
public class DegradedSchemeAppTestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> props = new HashMap<>();
        props.put("platform-config.apps[2].name", "scheme-broken-app");
        props.put("platform-config.apps[2].version-scheme", "calver");
        // calver-format deliberately absent: this is the defect under test.
        props.put("platform-config.apps[2].current.type", "http-json");
        props.put("platform-config.apps[2].current.url", "http://localhost:8089/filler/current");
        props.put("platform-config.apps[2].current.version-key", "/version");
        props.put("platform-config.apps[2].latest.type", "github-release");
        props.put("platform-config.apps[2].latest.repo", "example/scheme-broken-app");
        return props;
    }
}
