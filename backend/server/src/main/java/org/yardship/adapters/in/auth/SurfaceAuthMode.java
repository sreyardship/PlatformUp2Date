package org.yardship.adapters.in.auth;

import java.util.Optional;
import java.util.Set;

/**
 * Shared-issuer, role-gated boot-mode validation contract (docs/adr/0026, docs/adr/0028, issue
 * 01): derives whether each protected surface (currently just MCP) boots with OAuth enforced or
 * disabled from the presence of the shared issuer/audience pair ({@link #ISSUER_ENV_VAR}/
 * {@link #AUDIENCE_ENV_VAR}) plus a per-surface role var ({@link #MCP_ROLE_ENV_VAR}).
 *
 * <p>This generalizes the former MCP-only {@code McpOidcBootMode}: the issuer/audience pair used
 * to be MCP-specific; it is now the shared pair for the whole app, and MCP moves from "any valid
 * token is trusted" to "a valid token carrying the {@code pu2d-mcp} role is trusted"
 * (docs/adr/0028).
 *
 * <p>{@link #resolve} implements a presence-switched mode resolution:
 * <ul>
 *   <li>issuer, audience and role all blank/absent -&gt; {@link Disabled}</li>
 *   <li>issuer and audience present (non-blank) -&gt; {@link Enabled}, carrying issuer + audience
 *       and one {@link ProtectedSurface} for MCP, whose required role is the (raw, un-defaulted)
 *       {@code mcpRole} if present, else {@link #DEFAULT_MCP_ROLE}</li>
 *   <li>issuer present, audience blank/absent -&gt; fails fast, throwing
 *       {@link SurfaceAuthConfigurationException} whose message names {@link #AUDIENCE_ENV_VAR}
 *       (never a warning — a token minted for another audience on the same issuer must not be
 *       silently accepted). This check happens BEFORE any role handling: a role configured on an
 *       otherwise-invalid (issuer-without-audience) config must not mask the audience failure.</li>
 *   <li>issuer blank/absent, role present (non-blank) -&gt; fails fast, throwing
 *       {@link SurfaceAuthConfigurationException} whose message names {@link #ISSUER_ENV_VAR} — a
 *       role variable set by the operator is unambiguous evidence they intended auth on, so a role
 *       with nothing to validate against is a loud failure, never a silent {@link Disabled}. This
 *       is checked regardless of whether audience is present (audience alone never switches
 *       anything on, so a role stacked on top of "audience only" is still an issuer-less
 *       misconfiguration, not a mode of its own).</li>
 *   <li>issuer blank/absent, role blank/absent (audience may or may not be present) -&gt;
 *       {@link Disabled} — audience alone is inert; there is nothing to validate it against
 *       without an issuer.</li>
 * </ul>
 *
 * <p><b>Design decision (load-bearing — see {@code SurfaceAuthModeTests}):</b> {@code resolve}
 * takes the RAW (un-defaulted) {@code mcpRole} optional. Callers (the CDI boot announcer, the
 * config source factory) must NOT pre-apply the {@code pu2d-mcp} default via a SmallRye
 * {@code ${MCP_OIDC_ROLE:pu2d-mcp}} expansion before calling this method — doing so would make
 * {@code mcpRole} always appear "present", which would wrongly trip the role-without-issuer boot
 * failure even when the operator never set {@code MCP_OIDC_ROLE} at all. The {@link
 * #DEFAULT_MCP_ROLE} default is applied HERE, internally, and only in the branch that legally
 * builds {@link Enabled} (issuer and audience both present).
 *
 * <p>A blank string (e.g. the value of an unset {@code ${OIDC_ISSUER:}} SmallRye expansion, see
 * the existing {@code HARBOR_USER}/{@code HARBOR_PASS} idiom in application.yml) is treated
 * identically to an absent value, never as "set to empty".
 */
public sealed interface SurfaceAuthMode {

    String ISSUER_ENV_VAR = "OIDC_ISSUER";
    String AUDIENCE_ENV_VAR = "OIDC_AUDIENCE";
    String MCP_ROLE_ENV_VAR = "MCP_OIDC_ROLE";
    String DEFAULT_MCP_ROLE = "pu2d-mcp";

    static SurfaceAuthMode resolve(Optional<String> issuer, Optional<String> audience, Optional<String> mcpRole) {
        String issuerValue = blankToAbsent(issuer);
        String audienceValue = blankToAbsent(audience);
        String mcpRoleValue = blankToAbsent(mcpRole);

        if (issuerValue == null) {
            if (mcpRoleValue != null) {
                throw new SurfaceAuthConfigurationException(
                        MCP_ROLE_ENV_VAR + " is set but " + ISSUER_ENV_VAR + " is not — a role "
                                + "requirement needs an issuer to validate the token against.");
            }
            return new Disabled();
        }
        if (audienceValue == null) {
            throw new SurfaceAuthConfigurationException(
                    ISSUER_ENV_VAR + " is set but " + AUDIENCE_ENV_VAR + " is not — "
                            + AUDIENCE_ENV_VAR + " is mandatory whenever " + ISSUER_ENV_VAR
                            + " is configured, so a token minted for another audience on the same "
                            + "issuer cannot be replayed against this endpoint.");
        }

        String effectiveMcpRole = mcpRoleValue != null ? mcpRoleValue : DEFAULT_MCP_ROLE;
        ProtectedSurface mcpSurface = new ProtectedSurface("mcp", "/api/mcp*", effectiveMcpRole);
        return new Enabled(issuerValue, audienceValue, Set.of(mcpSurface));
    }

    private static String blankToAbsent(Optional<String> value) {
        return value.map(String::trim).filter(trimmed -> !trimmed.isEmpty()).orElse(null);
    }

    /** The single unambiguous startup log line for the resolved mode. */
    String logMessage();

    record Enabled(String issuer, String audience, Set<ProtectedSurface> protectedSurfaces) implements SurfaceAuthMode {
        @Override
        public String logMessage() {
            return "Surface authentication: enabled against issuer " + issuer;
        }
    }

    record Disabled() implements SurfaceAuthMode {
        @Override
        public String logMessage() {
            return "Surface authentication: disabled — protected surfaces rely on edge/network protection";
        }
    }

    /**
     * One protected surface (currently only MCP): the human-readable {@code name}, the HTTP
     * permission-policy {@code pathPattern} it applies to, and the realm role a caller must carry
     * to reach it.
     */
    record ProtectedSurface(String name, String pathPattern, String requiredRole) {
    }
}
