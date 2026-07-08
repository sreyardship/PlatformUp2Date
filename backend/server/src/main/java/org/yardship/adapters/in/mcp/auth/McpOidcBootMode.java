package org.yardship.adapters.in.mcp.auth;

import java.util.Optional;

/**
 * MCP endpoint boot-mode validation contract (docs/adr/0026, issue 01): derives whether the MCP
 * endpoint boots with OAuth enforced or disabled from the presence of the two operator-facing env
 * vars ({@link #ISSUER_ENV_VAR}/{@link #AUDIENCE_ENV_VAR}).
 *
 * <p>{@link #resolve} implements a presence-switched mode resolution:
 * <ul>
 *   <li>both blank/absent -&gt; {@link Disabled}</li>
 *   <li>both present (non-blank) -&gt; {@link Enforced}, carrying issuer + audience</li>
 *   <li>issuer present, audience blank/absent -&gt; fails fast, throwing
 *       {@link McpOidcConfigurationException} whose message names {@link #AUDIENCE_ENV_VAR}
 *       (never a warning — a token minted for another audience on the same issuer must not be
 *       silently accepted)</li>
 *   <li>audience present, issuer blank/absent -&gt; {@link Disabled} (audience alone does not
 *       switch anything on — there is nothing to validate it against without an issuer). This
 *       case is not silent: {@link McpOidcBootModeAnnouncer} emits a startup warning naming
 *       {@link #ISSUER_ENV_VAR} as probably missing or typo'd.</li>
 * </ul>
 * A blank string (e.g. the value of an unset {@code ${MCP_OIDC_ISSUER:}} SmallRye expansion, see
 * the existing {@code HARBOR_USER}/{@code HARBOR_PASS} idiom in application.yml) is treated
 * identically to an absent value, never as "set to empty".
 */
public sealed interface McpOidcBootMode {

    String ISSUER_ENV_VAR = "MCP_OIDC_ISSUER";
    String AUDIENCE_ENV_VAR = "MCP_OIDC_AUDIENCE";

    static McpOidcBootMode resolve(Optional<String> issuer, Optional<String> audience) {
        String issuerValue = blankToAbsent(issuer);
        String audienceValue = blankToAbsent(audience);

        if (issuerValue == null) {
            return new Disabled();
        }
        if (audienceValue == null) {
            throw new McpOidcConfigurationException(
                    ISSUER_ENV_VAR + " is set but " + AUDIENCE_ENV_VAR + " is not — "
                            + AUDIENCE_ENV_VAR + " is mandatory whenever " + ISSUER_ENV_VAR
                            + " is configured, so a token minted for another audience on the same "
                            + "issuer cannot be replayed against this endpoint.");
        }
        return new Enforced(issuerValue, audienceValue);
    }

    private static String blankToAbsent(Optional<String> value) {
        return value.map(String::trim).filter(trimmed -> !trimmed.isEmpty()).orElse(null);
    }

    /** The single unambiguous startup log line for the resolved mode. */
    String logMessage();

    record Enforced(String issuer, String audience) implements McpOidcBootMode {
        @Override
        public String logMessage() {
            return "MCP endpoint authentication: enforced against issuer " + issuer;
        }
    }

    record Disabled() implements McpOidcBootMode {
        @Override
        public String logMessage() {
            return "MCP endpoint authentication: disabled — endpoint relies on edge/network protection";
        }
    }
}
