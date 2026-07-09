package org.yardship.unit.adapters.in.auth;

import org.junit.jupiter.api.Test;
import org.yardship.adapters.in.auth.SurfaceAuthConfigurationException;
import org.yardship.adapters.in.auth.SurfaceAuthMode;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit contract for the shared-issuer, role-gated boot-mode validator (docs/adr/0026, docs/adr/
 * 0028, issues 01 + 02). Exercises {@link SurfaceAuthMode#resolve} directly — a plain-Java seam,
 * no CDI/Quarkus boot needed — covering the config contract's legal states and illegal states
 * (issuer-without-audience, role-without-issuer for EITHER surface's role var).
 *
 * <p><b>Signature evolution (issue 02):</b> {@code resolve} now takes a fourth argument,
 * {@code webRole}, mirroring {@code mcpRole} — both RAW (un-defaulted) optionals, per the same
 * contract established in issue 01.
 *
 * <p><b>BEHAVIOR CHANGE flagged for the implementer/reviewer — read before touching
 * {@code SurfaceAuthMode}:</b> issue 01 shipped MCP surface inclusion as unconditional once
 * issuer+audience are present (role absent/blank still built an {@code Enabled} carrying an MCP
 * {@link SurfaceAuthMode.ProtectedSurface} with the {@link SurfaceAuthMode#DEFAULT_MCP_ROLE}
 * default). Issue 02's contract requires the two surfaces to be <em>independently</em> switchable
 * — all four on/off combinations of {@code MCP_OIDC_ROLE} presence and {@code WEB_OIDC_ROLE}
 * presence must be expressible (see the issue's acceptance criterion "{@code WEB_OIDC_ROLE}
 * unset: {@code /api/v1} open regardless of MCP being on"). That is only satisfiable if EACH
 * surface's inclusion in {@code protectedSurfaces} is gated on that surface's OWN role var being
 * raw-present — not merely on issuer+audience presence. This test file therefore intentionally
 * REPLACES issue 01's two "role absent/blank -&gt; Enabled with default-role MCP surface" unit
 * tests with new ones asserting "role absent/blank -&gt; Enabled with NO mcp surface" (tenant on,
 * that surface permits). {@link SurfaceAuthMode#DEFAULT_MCP_ROLE} / the new
 * {@code DEFAULT_WEB_ROLE} constants are still pinned by name/value (operator-facing contract),
 * but are no longer exercised as an "applied when absent" fallback inside {@code resolve} for
 * surface inclusion — flag this to confirm the constants' purpose (documentation of the
 * recommended role name vs. a still-relevant runtime fallback) before/while implementing.
 */
class SurfaceAuthModeTests {

    private static final String ISSUER = "https://issuer.example.test/realms/yardship";
    private static final String AUDIENCE = "mcp-api";
    private static final String CUSTOM_MCP_ROLE = "custom-mcp-role";
    private static final String CUSTOM_WEB_ROLE = "custom-web-role";

    private static SurfaceAuthMode resolve(Optional<String> issuer, Optional<String> audience,
            Optional<String> mcpRole, Optional<String> webRole) {
        return SurfaceAuthMode.resolve(issuer, audience, mcpRole, webRole);
    }

    private static Optional<String> none() {
        return Optional.empty();
    }

    // --- Disabled: no issuer ---------------------------------------------------------------

    @Test
    void neitherIssuerNorAudienceNorAnyRolePresent_resolvesToDisabled() {
        SurfaceAuthMode mode = resolve(none(), none(), none(), none());

        assertInstanceOf(SurfaceAuthMode.Disabled.class, mode);
    }

    @Test
    void blankIssuerAudienceAndBothRoles_resolvesToDisabled() {
        // Mirrors the existing SmallRye ${VAR:} idiom used elsewhere in application.yml
        // (HARBOR_USER/HARBOR_PASS): an unset env var expands to "", not absent. Blank must be
        // treated the same as absent, never as "explicitly configured to empty".
        SurfaceAuthMode mode = resolve(Optional.of(""), Optional.of("  "), Optional.of(" "), Optional.of(" "));

        assertInstanceOf(SurfaceAuthMode.Disabled.class, mode);
    }

    @Test
    void audiencePresentWithoutIssuer_doesNotSwitchAuthOn() {
        // The presence SWITCH is the issuer alone. An audience configured without an issuer is
        // inert, not an error: there is nothing to validate the audience against without an
        // issuer.
        SurfaceAuthMode mode = resolve(none(), Optional.of(AUDIENCE), none(), none());

        assertInstanceOf(SurfaceAuthMode.Disabled.class, mode);
    }

    @Test
    void disabledMode_logMessage_saysDisabledAndReliesOnEdgeOrNetworkProtection() {
        SurfaceAuthMode mode = resolve(none(), none(), none(), none());

        String message = mode.logMessage().toLowerCase();
        assertTrue(message.contains("disabled"), "message must say disabled: " + message);
        assertTrue(message.contains("edge") || message.contains("network"),
                "message must attribute protection to edge/network: " + message);
    }

    // --- Enabled: issuer + audience present, NEITHER role present --------------------------
    // (issue 02 behavior change: tenant on, both surfaces permit — see class Javadoc.)

    @Test
    void issuerAndAudiencePresent_neitherRolePresent_resolvesToEnabled_withNoProtectedSurfaces() {
        SurfaceAuthMode mode = resolve(Optional.of(ISSUER), Optional.of(AUDIENCE), none(), none());

        assertInstanceOf(SurfaceAuthMode.Enabled.class, mode);
        SurfaceAuthMode.Enabled enabled = (SurfaceAuthMode.Enabled) mode;
        assertEquals(ISSUER, enabled.issuer());
        assertEquals(AUDIENCE, enabled.audience());
        assertEquals(Set.of(), enabled.protectedSurfaces(),
                "tenant on but neither role configured -> both surfaces permit (no protected surface)");
    }

    @Test
    void issuerAndAudiencePresent_bothRolesBlank_resolvesToEnabled_withNoProtectedSurfaces() {
        SurfaceAuthMode mode =
                resolve(Optional.of(ISSUER), Optional.of(AUDIENCE), Optional.of("  "), Optional.of("  "));

        SurfaceAuthMode.Enabled enabled = (SurfaceAuthMode.Enabled) mode;
        assertEquals(Set.of(), enabled.protectedSurfaces(),
                "blank role must be treated as absent, same as issue 01's blank-issuer/audience contract");
    }

    // --- Enabled: MCP role present, web role absent (mcp on / web off) ---------------------

    @Test
    void mcpRolePresent_webRoleAbsent_resolvesToEnabled_withOnlyMcpSurface() {
        SurfaceAuthMode mode =
                resolve(Optional.of(ISSUER), Optional.of(AUDIENCE), Optional.of(CUSTOM_MCP_ROLE), none());

        SurfaceAuthMode.Enabled enabled = (SurfaceAuthMode.Enabled) mode;
        assertEquals(1, enabled.protectedSurfaces().size());
        SurfaceAuthMode.ProtectedSurface mcpSurface = enabled.protectedSurfaces().iterator().next();
        assertEquals("mcp", mcpSurface.name());
        assertEquals("/api/mcp*", mcpSurface.pathPattern());
        assertEquals(CUSTOM_MCP_ROLE, mcpSurface.requiredRole());
    }

    // --- Enabled: web role present, MCP role absent (web on / mcp off) ---------------------

    @Test
    void webRolePresent_mcpRoleAbsent_resolvesToEnabled_withOnlyWebSurface() {
        SurfaceAuthMode mode =
                resolve(Optional.of(ISSUER), Optional.of(AUDIENCE), none(), Optional.of(CUSTOM_WEB_ROLE));

        SurfaceAuthMode.Enabled enabled = (SurfaceAuthMode.Enabled) mode;
        assertEquals(1, enabled.protectedSurfaces().size());
        SurfaceAuthMode.ProtectedSurface webSurface = enabled.protectedSurfaces().iterator().next();
        assertEquals("web", webSurface.name());
        assertEquals("/api/v1*", webSurface.pathPattern());
        assertEquals(CUSTOM_WEB_ROLE, webSurface.requiredRole());
    }

    @Test
    void webRolePresent_usesDefaultWebRole_whenConfiguredToTheDefaultValue() {
        // Pins the operator-facing default value; not a "default applied when absent" test (see
        // class Javadoc) — this simply confirms DEFAULT_WEB_ROLE is the value the docs/profile
        // tell operators to configure.
        SurfaceAuthMode mode = resolve(Optional.of(ISSUER), Optional.of(AUDIENCE), none(),
                Optional.of(SurfaceAuthMode.DEFAULT_WEB_ROLE));

        SurfaceAuthMode.Enabled enabled = (SurfaceAuthMode.Enabled) mode;
        SurfaceAuthMode.ProtectedSurface webSurface = enabled.protectedSurfaces().iterator().next();
        assertEquals(SurfaceAuthMode.DEFAULT_WEB_ROLE, webSurface.requiredRole());
    }

    // --- Enabled: BOTH roles present (mcp on / web on) --------------------------------------

    @Test
    void bothRolesPresent_resolvesToEnabled_withBothSurfaces() {
        SurfaceAuthMode mode = resolve(Optional.of(ISSUER), Optional.of(AUDIENCE),
                Optional.of(CUSTOM_MCP_ROLE), Optional.of(CUSTOM_WEB_ROLE));

        SurfaceAuthMode.Enabled enabled = (SurfaceAuthMode.Enabled) mode;
        assertEquals(2, enabled.protectedSurfaces().size());
        assertTrue(enabled.protectedSurfaces().stream()
                        .anyMatch(s -> s.name().equals("mcp") && s.pathPattern().equals("/api/mcp*")
                                && s.requiredRole().equals(CUSTOM_MCP_ROLE)),
                "mcp surface must be present with its configured role");
        assertTrue(enabled.protectedSurfaces().stream()
                        .anyMatch(s -> s.name().equals("web") && s.pathPattern().equals("/api/v1*")
                                && s.requiredRole().equals(CUSTOM_WEB_ROLE)),
                "web surface must be present with its configured role");
    }

    @Test
    void enabledMode_logMessage_namesTheIssuer() {
        SurfaceAuthMode mode = resolve(Optional.of(ISSUER), Optional.of(AUDIENCE),
                Optional.of(CUSTOM_MCP_ROLE), Optional.of(CUSTOM_WEB_ROLE));

        String message = mode.logMessage();
        assertTrue(message.contains(ISSUER), "message must name the issuer: " + message);
        assertTrue(!message.toLowerCase().contains("disabled"),
                "enabled mode's log line must not say disabled: " + message);
    }

    // --- Boot failure: issuer present, audience absent/blank --------------------------------

    @Test
    void issuerPresentWithoutAudience_failsBoot_withMessageNamingAudienceEnvVar() {
        SurfaceAuthConfigurationException failure = assertThrows(SurfaceAuthConfigurationException.class,
                () -> resolve(Optional.of(ISSUER), none(), none(), none()));

        assertTrue(failure.getMessage().contains("OIDC_AUDIENCE"),
                "boot failure must name OIDC_AUDIENCE so a typo is visible on first boot: "
                        + failure.getMessage());
    }

    @Test
    void issuerPresentWithBlankAudience_failsBoot_blankTreatedAsAbsent() {
        assertThrows(SurfaceAuthConfigurationException.class,
                () -> resolve(Optional.of(ISSUER), Optional.of("   "), none(), none()));
    }

    @Test
    void issuerAndMcpRolePresentWithoutAudience_failsBoot_withMessageNamingAudienceEnvVar() {
        // Audience is checked before role once an issuer is present: a role configured on an
        // otherwise-invalid (issuer-without-audience) config must not mask the audience failure.
        SurfaceAuthConfigurationException failure = assertThrows(SurfaceAuthConfigurationException.class,
                () -> resolve(Optional.of(ISSUER), none(), Optional.of(CUSTOM_MCP_ROLE), none()));

        assertTrue(failure.getMessage().contains("OIDC_AUDIENCE"), failure.getMessage());
    }

    @Test
    void issuerAndWebRolePresentWithoutAudience_failsBoot_withMessageNamingAudienceEnvVar() {
        SurfaceAuthConfigurationException failure = assertThrows(SurfaceAuthConfigurationException.class,
                () -> resolve(Optional.of(ISSUER), none(), none(), Optional.of(CUSTOM_WEB_ROLE)));

        assertTrue(failure.getMessage().contains("OIDC_AUDIENCE"), failure.getMessage());
    }

    // --- Boot failure: mcp role present, issuer absent/blank --------------------------------

    @Test
    void mcpRolePresentWithoutIssuer_failsBoot_withMessageNamingIssuerEnvVar() {
        SurfaceAuthConfigurationException failure = assertThrows(SurfaceAuthConfigurationException.class,
                () -> resolve(none(), none(), Optional.of(CUSTOM_MCP_ROLE), none()));

        assertTrue(failure.getMessage().contains("OIDC_ISSUER"),
                "boot failure must name OIDC_ISSUER so a typo'd/forgotten issuer var is visible: "
                        + failure.getMessage());
    }

    @Test
    void mcpRolePresentWithBlankIssuer_failsBoot_blankIssuerTreatedAsAbsent() {
        assertThrows(SurfaceAuthConfigurationException.class,
                () -> resolve(Optional.of("   "), none(), Optional.of(CUSTOM_MCP_ROLE), none()));
    }

    @Test
    void mcpRolePresentWithoutIssuerButWithAudience_stillFailsBoot_namingIssuerEnvVar() {
        // Audience alone never switches anything on (see audiencePresentWithoutIssuer_...
        // above); adding a role on top of that must not change the outcome to Disabled — a role
        // var set by the operator is unambiguous evidence they intended auth on, so this must be
        // a loud failure, never a silent Disabled.
        SurfaceAuthConfigurationException failure = assertThrows(SurfaceAuthConfigurationException.class,
                () -> resolve(none(), Optional.of(AUDIENCE), Optional.of(CUSTOM_MCP_ROLE), none()));

        assertTrue(failure.getMessage().contains("OIDC_ISSUER"), failure.getMessage());
    }

    @Test
    void blankMcpRoleWithoutIssuer_resolvesToDisabled_blankRoleTreatedAsAbsent() {
        SurfaceAuthMode mode = resolve(none(), none(), Optional.of("   "), none());

        assertInstanceOf(SurfaceAuthMode.Disabled.class, mode);
    }

    // --- NEW boot failure: web role present, issuer absent/blank ----------------------------
    // Same invariant as mcpRole, applied symmetrically to the new web role var (issue 02).

    @Test
    void webRolePresentWithoutIssuer_failsBoot_withMessageNamingIssuerEnvVar() {
        SurfaceAuthConfigurationException failure = assertThrows(SurfaceAuthConfigurationException.class,
                () -> resolve(none(), none(), none(), Optional.of(CUSTOM_WEB_ROLE)));

        assertTrue(failure.getMessage().contains("OIDC_ISSUER"),
                "boot failure must name OIDC_ISSUER so a typo'd/forgotten issuer var is visible: "
                        + failure.getMessage());
    }

    @Test
    void webRolePresentWithBlankIssuer_failsBoot_blankIssuerTreatedAsAbsent() {
        assertThrows(SurfaceAuthConfigurationException.class,
                () -> resolve(Optional.of("   "), none(), none(), Optional.of(CUSTOM_WEB_ROLE)));
    }

    @Test
    void webRolePresentWithoutIssuerButWithAudience_stillFailsBoot_namingIssuerEnvVar() {
        SurfaceAuthConfigurationException failure = assertThrows(SurfaceAuthConfigurationException.class,
                () -> resolve(none(), Optional.of(AUDIENCE), none(), Optional.of(CUSTOM_WEB_ROLE)));

        assertTrue(failure.getMessage().contains("OIDC_ISSUER"), failure.getMessage());
    }

    @Test
    void blankWebRoleWithoutIssuer_resolvesToDisabled_blankRoleTreatedAsAbsent() {
        SurfaceAuthMode mode = resolve(none(), none(), none(), Optional.of("   "));

        assertInstanceOf(SurfaceAuthMode.Disabled.class, mode);
    }

    @Test
    void bothRolesPresentWithoutIssuer_failsBoot_namingIssuerEnvVar() {
        // Either role alone is unambiguous evidence of operator intent; both present must still
        // fail loudly exactly once, naming the issuer var, never silently resolve as Disabled.
        SurfaceAuthConfigurationException failure = assertThrows(SurfaceAuthConfigurationException.class,
                () -> resolve(none(), none(), Optional.of(CUSTOM_MCP_ROLE), Optional.of(CUSTOM_WEB_ROLE)));

        assertTrue(failure.getMessage().contains("OIDC_ISSUER"), failure.getMessage());
    }

    // --- Env var name / default constants (pin the operator-facing surface) -----------------

    @Test
    void operatorFacingEnvVarNames_areTheSharedIssuerAudiencePairPlusBothRoles() {
        assertEquals("OIDC_ISSUER", SurfaceAuthMode.ISSUER_ENV_VAR);
        assertEquals("OIDC_AUDIENCE", SurfaceAuthMode.AUDIENCE_ENV_VAR);
        assertEquals("MCP_OIDC_ROLE", SurfaceAuthMode.MCP_ROLE_ENV_VAR);
        assertEquals("pu2d-mcp", SurfaceAuthMode.DEFAULT_MCP_ROLE);
        assertEquals("WEB_OIDC_ROLE", SurfaceAuthMode.WEB_ROLE_ENV_VAR);
        assertEquals("pu2d-web", SurfaceAuthMode.DEFAULT_WEB_ROLE);
    }
}
