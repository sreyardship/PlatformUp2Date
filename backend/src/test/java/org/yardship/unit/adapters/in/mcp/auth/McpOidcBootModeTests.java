package org.yardship.unit.adapters.in.mcp.auth;

import org.junit.jupiter.api.Test;
import org.yardship.adapters.in.mcp.auth.McpOidcBootMode;
import org.yardship.adapters.in.mcp.auth.McpOidcConfigurationException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit contract for the MCP OAuth boot-mode validator (docs/adr/0026, issue 01). Exercises
 * {@link McpOidcBootMode#resolve} directly — a plain-Java seam, no CDI/Quarkus boot needed —
 * covering the config contract's only two legal states and its one illegal state.
 *
 * <p>Currently RED: {@link McpOidcBootMode} is a TDD stub whose methods throw
 * {@link UnsupportedOperationException}. The implementer replaces the stub bodies to make these
 * pass; the test bodies themselves are the contract and should not need to change.
 */
class McpOidcBootModeTests {

    private static final String ISSUER = "https://issuer.example.test/realms/yardship";
    private static final String AUDIENCE = "mcp-api";

    @Test
    void neitherIssuerNorAudiencePresent_resolvesToDisabled() {
        McpOidcBootMode mode = McpOidcBootMode.resolve(Optional.empty(), Optional.empty());

        assertInstanceOf(McpOidcBootMode.Disabled.class, mode);
    }

    @Test
    void blankIssuerAndAudience_resolvesToDisabled() {
        // Mirrors the existing SmallRye ${VAR:} idiom used elsewhere in application.yml
        // (HARBOR_USER/HARBOR_PASS): an unset env var expands to "", not absent. Blank must be
        // treated the same as absent, never as "explicitly configured to empty".
        McpOidcBootMode mode = McpOidcBootMode.resolve(Optional.of(""), Optional.of("  "));

        assertInstanceOf(McpOidcBootMode.Disabled.class, mode);
    }

    @Test
    void disabledMode_logMessage_saysDisabledAndReliesOnEdgeOrNetworkProtection() {
        McpOidcBootMode mode = McpOidcBootMode.resolve(Optional.empty(), Optional.empty());

        String message = mode.logMessage().toLowerCase();
        assertTrue(message.contains("disabled"), "message must say disabled: " + message);
        assertTrue(message.contains("edge") || message.contains("network"),
                "message must attribute protection to edge/network: " + message);
    }

    @Test
    void issuerAndAudiencePresent_resolvesToEnforced_carryingBothValues() {
        McpOidcBootMode mode = McpOidcBootMode.resolve(Optional.of(ISSUER), Optional.of(AUDIENCE));

        assertInstanceOf(McpOidcBootMode.Enforced.class, mode);
        McpOidcBootMode.Enforced enforced = (McpOidcBootMode.Enforced) mode;
        assertEquals(ISSUER, enforced.issuer());
        assertEquals(AUDIENCE, enforced.audience());
    }

    @Test
    void enforcedMode_logMessage_saysEnforcedAndNamesTheIssuer() {
        McpOidcBootMode mode = McpOidcBootMode.resolve(Optional.of(ISSUER), Optional.of(AUDIENCE));

        String message = mode.logMessage();
        assertTrue(message.toLowerCase().contains("enforced"), "message must say enforced: " + message);
        assertTrue(message.contains(ISSUER), "message must name the issuer: " + message);
    }

    @Test
    void issuerPresentWithoutAudience_failsBoot_withMessageNamingAudienceEnvVar() {
        McpOidcConfigurationException failure = assertThrows(McpOidcConfigurationException.class,
                () -> McpOidcBootMode.resolve(Optional.of(ISSUER), Optional.empty()));

        assertTrue(failure.getMessage().contains("MCP_OIDC_AUDIENCE"),
                "boot failure must name MCP_OIDC_AUDIENCE so a typo is visible on first boot: "
                        + failure.getMessage());
    }

    @Test
    void issuerPresentWithBlankAudience_failsBoot_blankTreatedAsAbsent() {
        assertThrows(McpOidcConfigurationException.class,
                () -> McpOidcBootMode.resolve(Optional.of(ISSUER), Optional.of("   ")));
    }

    @Test
    void audiencePresentWithoutIssuer_doesNotSwitchAuthOn() {
        // Documented assumption — flag for implementer/reviewer: the presence SWITCH (ADR 0026)
        // is the issuer alone. An audience configured without an issuer is inert, not an error:
        // there is nothing to validate the audience against without an issuer.
        McpOidcBootMode mode = McpOidcBootMode.resolve(Optional.empty(), Optional.of(AUDIENCE));

        assertInstanceOf(McpOidcBootMode.Disabled.class, mode);
    }
}
