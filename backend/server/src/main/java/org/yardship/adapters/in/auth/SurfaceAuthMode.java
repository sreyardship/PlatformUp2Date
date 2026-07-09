package org.yardship.adapters.in.auth;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Shared-issuer, role-gated boot-mode validation contract (docs/adr/0026, docs/adr/0028, issues
 * 01 + 02): derives whether each protected surface (MCP and, as of issue 02, the REST API/"web"
 * surface) boots with OAuth enforced or disabled from the presence of the shared issuer/audience
 * pair ({@link #ISSUER_ENV_VAR}/{@link #AUDIENCE_ENV_VAR}) plus each surface's OWN role var
 * ({@link #MCP_ROLE_ENV_VAR}, {@link #WEB_ROLE_ENV_VAR}).
 *
 * <p>This generalizes the former MCP-only {@code McpOidcBootMode}: the issuer/audience pair used
 * to be MCP-specific; it is now the shared pair for the whole app. Each surface moves from "any
 * valid token is trusted" to "a valid token carrying that surface's role is trusted" (docs/adr/
 * 0028), and — as of issue 02 — the two surfaces are gated INDEPENDENTLY of one another: a surface
 * is only ever protected when its OWN role var is present, never merely because the tenant
 * (issuer+audience) is on or because the OTHER surface's role var is set.
 *
 * <p>{@link #resolve} implements a presence-switched mode resolution:
 * <ul>
 *   <li>issuer, audience and both roles all blank/absent -&gt; {@link Disabled}</li>
 *   <li>issuer and audience present (non-blank) -&gt; {@link Enabled}, carrying issuer + audience
 *       and one {@link ProtectedSurface} PER surface whose own role var is raw-present
 *       (non-blank); a surface whose role var is absent/blank is NOT added to
 *       {@link Enabled#protectedSurfaces()} — its HTTP policy stays {@code permit} (open) even
 *       though the tenant itself is on. Both role vars absent yields {@link Enabled} with an
 *       EMPTY {@code protectedSurfaces} set (tenant on, both surfaces open) — see
 *       {@code SurfaceAuthModeTests} for why this must never default a surface into existence.</li>
 *   <li>issuer present, audience blank/absent -&gt; fails fast, throwing
 *       {@link SurfaceAuthConfigurationException} whose message names {@link #AUDIENCE_ENV_VAR}
 *       (never a warning — a token minted for another audience on the same issuer must not be
 *       silently accepted). This check happens BEFORE any role handling: a role configured on an
 *       otherwise-invalid (issuer-without-audience) config must not mask the audience failure.</li>
 *   <li>issuer blank/absent, EITHER role present (non-blank) -&gt; fails fast, throwing
 *       {@link SurfaceAuthConfigurationException} whose message names {@link #ISSUER_ENV_VAR} — a
 *       role variable set by the operator (for either surface) is unambiguous evidence they
 *       intended auth on, so a role with nothing to validate against is a loud failure, never a
 *       silent {@link Disabled}. This is checked regardless of whether audience is present
 *       (audience alone never switches anything on, so a role stacked on top of "audience only" is
 *       still an issuer-less misconfiguration, not a mode of its own).</li>
 *   <li>issuer blank/absent, both roles blank/absent (audience may or may not be present) -&gt;
 *       {@link Disabled} — audience alone is inert; there is nothing to validate it against
 *       without an issuer.</li>
 * </ul>
 *
 * <p><b>Design decision (load-bearing — see {@code SurfaceAuthModeTests}):</b> {@code resolve}
 * takes the RAW (un-defaulted) {@code mcpRole}/{@code webRole} optionals. Callers (the CDI boot
 * announcer, the config source factory) must NOT pre-apply a {@code ${..:pu2d-mcp}}-style default
 * via a SmallRye expansion before calling this method — doing so would make the role always
 * appear "present", which would wrongly trip the role-without-issuer boot failure even when the
 * operator never set the var at all, AND would wrongly gate a surface the operator never asked to
 * protect. {@link #DEFAULT_MCP_ROLE}/{@link #DEFAULT_WEB_ROLE} are NOT applied as a presence
 * fallback inside {@code resolve} (that would re-introduce always-on gating for that surface).
 * They are purely the DOCUMENTED CONVENTIONAL role NAME an operator is expected to grant/configure
 * — the value referenced by tests, docs and deploy manifests — kept as public constants because
 * both are pinned by name/value there. When a surface's role var IS raw-present, the required role
 * is exactly that raw value, verbatim, never substituted.
 *
 * <p>A blank string (e.g. the value of an unset {@code ${OIDC_ISSUER:}} SmallRye expansion, see
 * the existing {@code HARBOR_USER}/{@code HARBOR_PASS} idiom in application.yml) is treated
 * identically to an absent value, never as "set to empty".
 */
public sealed interface SurfaceAuthMode {

    String ISSUER_ENV_VAR = "OIDC_ISSUER";
    String AUDIENCE_ENV_VAR = "OIDC_AUDIENCE";
    String MCP_ROLE_ENV_VAR = "MCP_OIDC_ROLE";
    /** Conventional/recommended role NAME for the MCP surface — see class Javadoc. */
    String DEFAULT_MCP_ROLE = "pu2d-mcp";
    String WEB_ROLE_ENV_VAR = "WEB_OIDC_ROLE";
    /** Conventional/recommended role NAME for the web surface — see class Javadoc. */
    String DEFAULT_WEB_ROLE = "pu2d-web";

    static SurfaceAuthMode resolve(Optional<String> issuer, Optional<String> audience,
            Optional<String> mcpRole, Optional<String> webRole) {
        String issuerValue = blankToAbsent(issuer);
        String audienceValue = blankToAbsent(audience);
        String mcpRoleValue = blankToAbsent(mcpRole);
        String webRoleValue = blankToAbsent(webRole);

        if (issuerValue == null) {
            if (mcpRoleValue != null) {
                throw new SurfaceAuthConfigurationException(
                        MCP_ROLE_ENV_VAR + " is set but " + ISSUER_ENV_VAR + " is not — a role "
                                + "requirement needs an issuer to validate the token against.");
            }
            if (webRoleValue != null) {
                throw new SurfaceAuthConfigurationException(
                        WEB_ROLE_ENV_VAR + " is set but " + ISSUER_ENV_VAR + " is not — a role "
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

        Set<ProtectedSurface> protectedSurfaces = new HashSet<>();
        if (mcpRoleValue != null) {
            protectedSurfaces.add(new ProtectedSurface("mcp", "/api/mcp*", mcpRoleValue));
        }
        if (webRoleValue != null) {
            protectedSurfaces.add(new ProtectedSurface("web", "/api/v1*", webRoleValue));
        }
        return new Enabled(issuerValue, audienceValue, Set.copyOf(protectedSurfaces));
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
     * One protected surface (MCP or web): the human-readable {@code name}, the HTTP
     * permission-policy {@code pathPattern} it applies to, and the realm role a caller must carry
     * to reach it.
     */
    record ProtectedSurface(String name, String pathPattern, String requiredRole) {
    }
}
