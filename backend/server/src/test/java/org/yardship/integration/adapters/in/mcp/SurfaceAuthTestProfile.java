package org.yardship.integration.adapters.in.mcp;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Auth-ON profile for the shared-issuer, role-gated model (docs/adr/0026, docs/adr/0028, issue
 * 01) — the generalization of the former MCP-only {@code McpOidcAuthTestProfile}. Turns on
 * Keycloak Dev Services against a fixed realm ({@code mcp-oidc-test-realm.json}, in
 * src/test/resources):
 * <ul>
 *   <li>{@code mcp-client} mints tokens carrying the {@link #CONFIGURED_AUDIENCE} audience —
 *       the audience this profile configures the app to require;</li>
 *   <li>{@code other-client} mints tokens carrying {@link #WRONG_AUDIENCE} — same issuer
 *       (same realm/Dev Services container), different audience, for the mismatch case;</li>
 *   <li>{@code alice} carries the {@code pu2d-mcp} realm role (the 200/happy-path user);</li>
 *   <li>{@code bob} does NOT carry {@code pu2d-mcp} (the 403/role-missing case), but can still
 *       mint a token with the right audience via {@code mcp-client}.</li>
 * </ul>
 * Real issuer discovery + JWKS run against the Dev Services container; no identity is mocked.
 *
 * <p>Sets the three now-shared/generalized operator-facing vars: {@code OIDC_ISSUER} (derived
 * from the Dev-Services-assigned {@code quarkus.oidc.auth-server-url}, via a SmallRye property
 * expression resolved lazily once Dev Services has actually started the container),
 * {@code OIDC_AUDIENCE}, and {@code MCP_OIDC_ROLE=pu2d-mcp} (explicit here rather than relying on
 * the production default, so this profile pins the exact role name the realm fixture grants to
 * {@code alice}).
 */
public class SurfaceAuthTestProfile implements QuarkusTestProfile {

    public static final String CONFIGURED_AUDIENCE = "mcp-api";
    public static final String WRONG_AUDIENCE = "other-api";

    public static final String RIGHT_AUDIENCE_CLIENT_ID = "mcp-client";
    public static final String WRONG_AUDIENCE_CLIENT_ID = "other-client";
    public static final String TEST_CLIENT_SECRET = "secret";

    public static final String TEST_USERNAME = "alice";
    public static final String TEST_PASSWORD = "alice";

    /** Carries the right audience client but lacks the pu2d-mcp realm role — the 403 case. */
    public static final String NO_ROLE_USERNAME = "bob";
    public static final String NO_ROLE_PASSWORD = "bob";

    public static final String MCP_ROLE = "pu2d-mcp";

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
                // DERIVED (by SurfaceAuthConfigSourceFactory) from OIDC_ISSUER, which this profile
                // in turn derives FROM the Dev-Services-assigned auth-server-url: a genuine
                // chicken-and-egg at build time, since OIDC_ISSUER cannot resolve before the
                // container exists. This flag breaks that cycle by telling Dev Services to start
                // regardless of the (not-yet-derivable) tenant-enabled state.
                "quarkus.keycloak.devservices.start-with-disabled-tenant", "true",
                // The realm import above already defines both clients this test needs; skip Dev
                // Services' own auto-provisioned confidential client so quarkus-oidc has no
                // client-id/secret to fall back to token introspection with (we want pure local
                // JWT/JWKS verification, matching a bearer-only resource server).
                "quarkus.keycloak.devservices.create-client", "false",
                "OIDC_ISSUER", "${quarkus.oidc.auth-server-url}",
                "OIDC_AUDIENCE", CONFIGURED_AUDIENCE,
                "MCP_OIDC_ROLE", MCP_ROLE,
                // No Redis dev service in this session — nothing here exercises Redis (the
                // version port is mocked; /q/health's Redis probe result is never asserted).
                // This is not just a speed-up: Quarkus registers each started dev service with
                // the SESSION-WIDE merged config map (DevServicesRegistryBuildItem#reallyStart
                // passes the shared `configs` map into every RunningService), and the Keycloak
                // entry of this profile's session outlives the profile switch (its registry key
                // carries this session's application UUID, so the next session's cleanup skips
                // it). If a Redis dev service also ran here, the lingering Keycloak entry would
                // keep advertising THIS session's (by then stopped) quarkus.redis.hosts through
                // DevServicesConfigSource, and later default-profile Valkey ITs would
                // intermittently resolve the dead port ("Connection refused") instead of their
                // own fresh container. Pointing hosts at a closed localhost port keeps the dev
                // service off and the shared map free of any redis key.
                "quarkus.redis.devservices.enabled", "false",
                "quarkus.redis.hosts", "redis://localhost:63790");
    }
}
