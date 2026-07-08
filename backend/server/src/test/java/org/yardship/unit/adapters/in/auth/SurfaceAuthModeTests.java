package org.yardship.unit.adapters.in.auth;

import org.junit.jupiter.api.Test;
import org.yardship.adapters.in.auth.SurfaceAuthConfigurationException;
import org.yardship.adapters.in.auth.SurfaceAuthMode;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit contract for the shared-issuer, role-gated boot-mode validator (docs/adr/0026, docs/adr/
 * 0028, issue 01) — the generalization of the former MCP-only {@code McpOidcBootMode}. Exercises
 * {@link SurfaceAuthMode#resolve} directly — a plain-Java seam, no CDI/Quarkus boot needed —
 * covering the config contract's legal states and both illegal states (issuer-without-audience,
 * and the new role-without-issuer).
 *
 * <p><b>Design decision flagged for the implementer/reviewer:</b> {@link SurfaceAuthMode#resolve}
 * takes the RAW (un-defaulted) {@code mcpRole} optional — i.e. callers must NOT pre-apply the
 * {@code pu2d-mcp} default before calling {@code resolve} (do not read the property via a
 * SmallRye {@code ${MCP_OIDC_ROLE:pu2d-mcp}} expansion upstream). This is required for the module
 * to stay a pure, side-effect-free function AND satisfy two acceptance criteria that would
 * otherwise conflict:
 * <ul>
 *   <li>"No OIDC_ISSUER: /api/mcp is open exactly as today" — if the caller pre-applied the
 *       {@code pu2d-mcp} default, {@code mcpRole} would ALWAYS appear "present" even when the
 *       operator never set {@code MCP_OIDC_ROLE}, which would make the role-without-issuer boot
 *       failure fire unconditionally whenever {@code OIDC_ISSUER} is absent — breaking the
 *       auth-off default.</li>
 *   <li>"MCP_OIDC_ROLE set, OIDC_ISSUER absent -&gt; boot failure naming OIDC_ISSUER" only makes
 *       sense as "the operator explicitly configured a role", which requires the raw (undefaulted)
 *       presence signal.</li>
 * </ul>
 * So {@code resolve} itself applies the {@code pu2d-mcp} default internally, but ONLY in the
 * branch where {@code issuer} is present and legal (i.e. only when actually building the
 * {@code Enabled} state's {@code /api/mcp*} protected surface) — never as part of the
 * presence/absence check used for the role-without-issuer boot failure. Both
 * {@code SurfaceAuthBootAnnouncer} (the {@code @ConfigProperty}-injecting CDI observer) and
 * {@code SurfaceAuthConfigSourceFactory} must therefore pass the RAW {@code MCP_OIDC_ROLE} value
 * (no {@code :pu2d-mcp} default in the property expression) straight into {@code resolve}.
 */
class SurfaceAuthModeTests {

    private static final String ISSUER = "https://issuer.example.test/realms/yardship";
    private static final String AUDIENCE = "mcp-api";
    private static final String CUSTOM_ROLE = "custom-mcp-role";
    private static final String DEFAULT_ROLE = SurfaceAuthMode.DEFAULT_MCP_ROLE;

    // --- Disabled: no issuer ---------------------------------------------------------------

    @Test
    void neitherIssuerNorAudienceNorRolePresent_resolvesToDisabled() {
        SurfaceAuthMode mode = SurfaceAuthMode.resolve(Optional.empty(), Optional.empty(), Optional.empty());

        assertInstanceOf(SurfaceAuthMode.Disabled.class, mode);
    }

    @Test
    void blankIssuerAudienceAndRole_resolvesToDisabled() {
        // Mirrors the existing SmallRye ${VAR:} idiom used elsewhere in application.yml
        // (HARBOR_USER/HARBOR_PASS): an unset env var expands to "", not absent. Blank must be
        // treated the same as absent, never as "explicitly configured to empty".
        SurfaceAuthMode mode = SurfaceAuthMode.resolve(Optional.of(""), Optional.of("  "), Optional.of(" "));

        assertInstanceOf(SurfaceAuthMode.Disabled.class, mode);
    }

    @Test
    void audiencePresentWithoutIssuer_doesNotSwitchAuthOn() {
        // The presence SWITCH is the issuer alone. An audience configured without an issuer is
        // inert, not an error: there is nothing to validate the audience against without an
        // issuer.
        SurfaceAuthMode mode = SurfaceAuthMode.resolve(Optional.empty(), Optional.of(AUDIENCE), Optional.empty());

        assertInstanceOf(SurfaceAuthMode.Disabled.class, mode);
    }

    @Test
    void disabledMode_logMessage_saysDisabledAndReliesOnEdgeOrNetworkProtection() {
        SurfaceAuthMode mode = SurfaceAuthMode.resolve(Optional.empty(), Optional.empty(), Optional.empty());

        String message = mode.logMessage().toLowerCase();
        assertTrue(message.contains("disabled"), "message must say disabled: " + message);
        assertTrue(message.contains("edge") || message.contains("network"),
                "message must attribute protection to edge/network: " + message);
    }

    // --- Enabled: issuer + audience present -------------------------------------------------

    @Test
    void issuerAndAudiencePresent_roleAbsent_resolvesToEnabled_withDefaultMcpRoleSurface() {
        SurfaceAuthMode mode = SurfaceAuthMode.resolve(Optional.of(ISSUER), Optional.of(AUDIENCE), Optional.empty());

        assertInstanceOf(SurfaceAuthMode.Enabled.class, mode);
        SurfaceAuthMode.Enabled enabled = (SurfaceAuthMode.Enabled) mode;
        assertEquals(ISSUER, enabled.issuer());
        assertEquals(AUDIENCE, enabled.audience());
        assertEquals(1, enabled.protectedSurfaces().size());
        SurfaceAuthMode.ProtectedSurface mcpSurface = enabled.protectedSurfaces().iterator().next();
        assertEquals("/api/mcp*", mcpSurface.pathPattern());
        assertEquals(DEFAULT_ROLE, mcpSurface.requiredRole(),
                "MCP_OIDC_ROLE absent must fall back to the pu2d-mcp default");
    }

    @Test
    void issuerAudienceAndRolePresent_resolvesToEnabled_surfaceRequiresConfiguredRole() {
        SurfaceAuthMode mode =
                SurfaceAuthMode.resolve(Optional.of(ISSUER), Optional.of(AUDIENCE), Optional.of(CUSTOM_ROLE));

        assertInstanceOf(SurfaceAuthMode.Enabled.class, mode);
        SurfaceAuthMode.Enabled enabled = (SurfaceAuthMode.Enabled) mode;
        assertEquals(ISSUER, enabled.issuer());
        assertEquals(AUDIENCE, enabled.audience());
        SurfaceAuthMode.ProtectedSurface mcpSurface = enabled.protectedSurfaces().iterator().next();
        assertEquals("/api/mcp*", mcpSurface.pathPattern());
        assertEquals(CUSTOM_ROLE, mcpSurface.requiredRole());
    }

    @Test
    void issuerAudienceAndBlankRole_resolvesToEnabled_withDefaultMcpRoleSurface() {
        SurfaceAuthMode mode = SurfaceAuthMode.resolve(Optional.of(ISSUER), Optional.of(AUDIENCE), Optional.of("  "));

        SurfaceAuthMode.Enabled enabled = (SurfaceAuthMode.Enabled) mode;
        SurfaceAuthMode.ProtectedSurface mcpSurface = enabled.protectedSurfaces().iterator().next();
        assertEquals(DEFAULT_ROLE, mcpSurface.requiredRole());
    }

    @Test
    void enabledMode_logMessage_namesTheIssuer() {
        SurfaceAuthMode mode = SurfaceAuthMode.resolve(Optional.of(ISSUER), Optional.of(AUDIENCE), Optional.of(CUSTOM_ROLE));

        String message = mode.logMessage();
        assertTrue(message.contains(ISSUER), "message must name the issuer: " + message);
        assertTrue(!message.toLowerCase().contains("disabled"),
                "enabled mode's log line must not say disabled: " + message);
    }

    // --- Boot failure: issuer present, audience absent/blank --------------------------------

    @Test
    void issuerPresentWithoutAudience_failsBoot_withMessageNamingAudienceEnvVar() {
        SurfaceAuthConfigurationException failure = assertThrows(SurfaceAuthConfigurationException.class,
                () -> SurfaceAuthMode.resolve(Optional.of(ISSUER), Optional.empty(), Optional.empty()));

        assertTrue(failure.getMessage().contains("OIDC_AUDIENCE"),
                "boot failure must name OIDC_AUDIENCE so a typo is visible on first boot: "
                        + failure.getMessage());
    }

    @Test
    void issuerPresentWithBlankAudience_failsBoot_blankTreatedAsAbsent() {
        assertThrows(SurfaceAuthConfigurationException.class,
                () -> SurfaceAuthMode.resolve(Optional.of(ISSUER), Optional.of("   "), Optional.empty()));
    }

    @Test
    void issuerAndRolePresentWithoutAudience_failsBoot_withMessageNamingAudienceEnvVar() {
        // Audience is checked before role once an issuer is present: a role configured on an
        // otherwise-invalid (issuer-without-audience) config must not mask the audience failure.
        SurfaceAuthConfigurationException failure = assertThrows(SurfaceAuthConfigurationException.class,
                () -> SurfaceAuthMode.resolve(Optional.of(ISSUER), Optional.empty(), Optional.of(CUSTOM_ROLE)));

        assertTrue(failure.getMessage().contains("OIDC_AUDIENCE"), failure.getMessage());
    }

    // --- NEW boot failure: mcp role present, issuer absent/blank ----------------------------

    @Test
    void mcpRolePresentWithoutIssuer_failsBoot_withMessageNamingIssuerEnvVar() {
        SurfaceAuthConfigurationException failure = assertThrows(SurfaceAuthConfigurationException.class,
                () -> SurfaceAuthMode.resolve(Optional.empty(), Optional.empty(), Optional.of(CUSTOM_ROLE)));

        assertTrue(failure.getMessage().contains("OIDC_ISSUER"),
                "boot failure must name OIDC_ISSUER so a typo'd/forgotten issuer var is visible: "
                        + failure.getMessage());
    }

    @Test
    void mcpRolePresentWithBlankIssuer_failsBoot_blankIssuerTreatedAsAbsent() {
        assertThrows(SurfaceAuthConfigurationException.class,
                () -> SurfaceAuthMode.resolve(Optional.of("   "), Optional.empty(), Optional.of(CUSTOM_ROLE)));
    }

    @Test
    void mcpRolePresentWithoutIssuerButWithAudience_stillFailsBoot_namingIssuerEnvVar() {
        // Audience alone never switches anything on (see audiencePresentWithoutIssuer_...
        // above); adding a role on top of that must not change the outcome to Disabled — a role
        // var set by the operator is unambiguous evidence they intended auth on, so this must be
        // a loud failure, never a silent Disabled.
        SurfaceAuthConfigurationException failure = assertThrows(SurfaceAuthConfigurationException.class,
                () -> SurfaceAuthMode.resolve(Optional.empty(), Optional.of(AUDIENCE), Optional.of(CUSTOM_ROLE)));

        assertTrue(failure.getMessage().contains("OIDC_ISSUER"), failure.getMessage());
    }

    @Test
    void blankMcpRoleWithoutIssuer_resolvesToDisabled_blankRoleTreatedAsAbsent() {
        SurfaceAuthMode mode = SurfaceAuthMode.resolve(Optional.empty(), Optional.empty(), Optional.of("   "));

        assertInstanceOf(SurfaceAuthMode.Disabled.class, mode);
    }

    // --- Env var name / default constants (pin the operator-facing surface) -----------------

    @Test
    void operatorFacingEnvVarNames_areTheSharedIssuerAudiencePairPlusMcpRole() {
        assertEquals("OIDC_ISSUER", SurfaceAuthMode.ISSUER_ENV_VAR);
        assertEquals("OIDC_AUDIENCE", SurfaceAuthMode.AUDIENCE_ENV_VAR);
        assertEquals("MCP_OIDC_ROLE", SurfaceAuthMode.MCP_ROLE_ENV_VAR);
        assertEquals("pu2d-mcp", SurfaceAuthMode.DEFAULT_MCP_ROLE);
    }
}
