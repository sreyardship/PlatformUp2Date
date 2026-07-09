package org.yardship.integration.adapters.in.mcp;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Auth-ON profile for the <b>both surfaces gated</b> corner of the four on/off combinations
 * (issue 02, docs/adr/0028): sets BOTH {@code MCP_OIDC_ROLE} and {@code WEB_OIDC_ROLE}, so
 * {@code /api/v1} and {@code /api/mcp} are each independently role-gated against the SAME shared
 * issuer+audience. This is the profile the cross-surface isolation IT
 * ({@link CrossSurfaceIsolationIT}) and the web-surface enforcement IT ({@link WebAuthEnforcedIT})
 * run under — the headline proof that a web-only token is accepted on {@code /api/v1} but 403 on
 * {@code /api/mcp}, and vice versa, even though both surfaces share one audience. Same realm/Dev
 * Services/audience setup as {@link SurfaceAuthTestProfile} — see that class's Javadoc for the
 * realm's users/clients (alice: both roles, bob: neither, wanda: web-only, mona: mcp-only).
 */
public class BothSurfacesAuthTestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.keycloak.devservices.enabled", "true",
                "quarkus.keycloak.devservices.realm-path", "mcp-oidc-test-realm.json",
                "quarkus.keycloak.devservices.start-with-disabled-tenant", "true",
                "quarkus.keycloak.devservices.create-client", "false",
                "OIDC_ISSUER", "${quarkus.oidc.auth-server-url}",
                "OIDC_AUDIENCE", SurfaceAuthTestProfile.CONFIGURED_AUDIENCE,
                "MCP_OIDC_ROLE", SurfaceAuthTestProfile.MCP_ROLE,
                "WEB_OIDC_ROLE", SurfaceAuthTestProfile.WEB_ROLE,
                // No Redis dev service needed here either — see SurfaceAuthTestProfile's Javadoc
                // for why this matters across profile switches within the same test session.
                "quarkus.redis.devservices.enabled", "false",
                "quarkus.redis.hosts", "redis://localhost:63790");
    }
}
