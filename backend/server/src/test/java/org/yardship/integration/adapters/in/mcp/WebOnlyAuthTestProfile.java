package org.yardship.integration.adapters.in.mcp;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Auth-ON profile for the <b>web-only</b> corner of the four surface on/off combinations (issue
 * 02, docs/adr/0028): sets {@code WEB_OIDC_ROLE} but deliberately leaves {@code MCP_OIDC_ROLE}
 * unset, so {@code /api/v1} is gated while {@code /api/mcp} stays open — the mirror image of
 * {@link SurfaceAuthTestProfile} (mcp-only). Same shared realm/Dev Services/audience setup as
 * {@link SurfaceAuthTestProfile} — see that class's Javadoc for the realm's users/clients, which
 * this profile reuses unchanged (only the two role env vars differ between the sibling
 * profiles).
 */
public class WebOnlyAuthTestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.keycloak.devservices.enabled", "true",
                "quarkus.keycloak.devservices.realm-path", "mcp-oidc-test-realm.json",
                "quarkus.keycloak.devservices.start-with-disabled-tenant", "true",
                "quarkus.keycloak.devservices.create-client", "false",
                "OIDC_ISSUER", "${quarkus.oidc.auth-server-url}",
                "OIDC_AUDIENCE", SurfaceAuthTestProfile.CONFIGURED_AUDIENCE,
                "WEB_OIDC_ROLE", SurfaceAuthTestProfile.WEB_ROLE,
                // MCP_OIDC_ROLE deliberately absent: /api/mcp must stay open in this profile.
                // No Redis dev service needed here either — see SurfaceAuthTestProfile's Javadoc
                // for why this matters across profile switches within the same test session.
                "quarkus.redis.devservices.enabled", "false",
                "quarkus.redis.hosts", "redis://localhost:63790");
    }
}
