package org.yardship.adapters.in.mcp.auth;

/**
 * Thrown at boot when the MCP OAuth config contract (docs/adr/0026) is in its one illegal state:
 * {@link McpOidcBootMode#ISSUER_ENV_VAR} is set but {@link McpOidcBootMode#AUDIENCE_ENV_VAR} is
 * not. This must fail application startup — never degrade to a warning — and the message must
 * name {@code MCP_OIDC_AUDIENCE} so a typo'd env var is visible on first boot.
 */
public class McpOidcConfigurationException extends RuntimeException {

    public McpOidcConfigurationException(String message) {
        super(message);
    }
}
