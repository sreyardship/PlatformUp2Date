package org.yardship.integration.adapters.in.mcp;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Auth-ON profile for the MCP endpoint (docs/adr/0026, issue 01). Turns on Keycloak Dev Services
 * against a fixed two-client realm ({@code mcp-oidc-test-realm.json}, in src/test/resources):
 * <ul>
 *   <li>{@code mcp-client} mints tokens carrying the {@link #CONFIGURED_AUDIENCE} audience —
 *       the audience this profile configures the app to require;</li>
 *   <li>{@code other-client} mints tokens carrying {@link #WRONG_AUDIENCE} — same issuer
 *       (same realm/Dev Services container), different audience, for the mismatch case.</li>
 * </ul>
 * Real issuer discovery + JWKS run against the Dev Services container; no identity is mocked.
 *
 * <p><b>Assumption flagged for the implementer/reviewer:</b> the two operator-facing env vars
 * (docs/adr/0026: "these two env vars are the whole operator contract") are assumed to be
 * consumed directly as MicroProfile Config property names {@code MCP_OIDC_ISSUER} /
 * {@code MCP_OIDC_AUDIENCE} — i.e. no separate dotted/lowercase alias — so this profile overrides
 * those two keys by that exact name. If the real implementation instead reads a different
 * (e.g. dotted) internal property name and merely derives it from these env vars, update the two
 * keys below to match; nothing else in this test should need to change. The issuer's value can't
 * be known until Dev Services has actually started the container (dynamic port), so it is wired
 * through a SmallRye property expression referencing the Dev-Services-populated
 * {@code quarkus.oidc.auth-server-url}, resolved lazily when the app reads MCP_OIDC_ISSUER at boot.
 */
public class McpOidcAuthTestProfile implements QuarkusTestProfile {

    public static final String CONFIGURED_AUDIENCE = "mcp-api";
    public static final String WRONG_AUDIENCE = "other-api";

    public static final String RIGHT_AUDIENCE_CLIENT_ID = "mcp-client";
    public static final String WRONG_AUDIENCE_CLIENT_ID = "other-client";
    public static final String TEST_CLIENT_SECRET = "secret";
    public static final String TEST_USERNAME = "alice";
    public static final String TEST_PASSWORD = "alice";

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                // application.yml disables Keycloak Dev Services by default (so the auth-off
                // regression suite stays Docker-free); this profile re-enables it explicitly so
                // real issuer discovery/JWKS is available for the auth-on ITs.
                "quarkus.keycloak.devservices.enabled", "true",
                "quarkus.keycloak.devservices.realm-path", "mcp-oidc-test-realm.json",
                // Dev Services' own trigger (KeycloakDevServiceRequiredBuildStep) only starts the
                // container when it sees quarkus.oidc.tenant-enabled=true — but that flag is itself
                // DERIVED (by McpOidcRuntimeConfigSourceFactory) from MCP_OIDC_ISSUER, which this
                // profile in turn derives FROM the Dev-Services-assigned auth-server-url: a genuine
                // chicken-and-egg at build time, since MCP_OIDC_ISSUER cannot resolve before the
                // container exists. This flag breaks that cycle by telling Dev Services to start
                // regardless of the (not-yet-derivable) tenant-enabled state.
                "quarkus.keycloak.devservices.start-with-disabled-tenant", "true",
                // The realm import above already defines both clients this test needs; skip Dev
                // Services' own auto-provisioned confidential client so quarkus-oidc has no
                // client-id/secret to fall back to token introspection with (we want pure local
                // JWT/JWKS verification, matching a bearer-only resource server).
                "quarkus.keycloak.devservices.create-client", "false",
                "MCP_OIDC_ISSUER", "${quarkus.oidc.auth-server-url}",
                "MCP_OIDC_AUDIENCE", CONFIGURED_AUDIENCE);
    }
}
