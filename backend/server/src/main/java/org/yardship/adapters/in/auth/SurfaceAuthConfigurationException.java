package org.yardship.adapters.in.auth;

/**
 * Thrown at boot when the shared-issuer, role-gated auth config contract (docs/adr/0026, docs/adr/
 * 0028) is in one of its illegal states:
 * <ul>
 *   <li>{@link SurfaceAuthMode#ISSUER_ENV_VAR} is set but {@link SurfaceAuthMode#AUDIENCE_ENV_VAR}
 *       is not — the message names {@code OIDC_AUDIENCE};</li>
 *   <li>{@link SurfaceAuthMode#MCP_ROLE_ENV_VAR} (or another surface's role var) is set but
 *       {@link SurfaceAuthMode#ISSUER_ENV_VAR} is not — the message names {@code OIDC_ISSUER}.</li>
 * </ul>
 * Either case must fail application startup — never degrade to a warning — so a typo'd env var is
 * visible on first boot.
 */
public class SurfaceAuthConfigurationException extends RuntimeException {

    public SurfaceAuthConfigurationException(String message) {
        super(message);
    }
}
