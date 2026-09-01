package org.yardship.integration.adapters.out.versionsource.latest.githubrelease;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/** Allows the local self-signed HTTPS redirect fixture to be reached. */
public class GithubReleaseRedirectAuthorizationTestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("quarkus.tls.trust-all", "true");
    }
}
