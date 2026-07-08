package org.yardship.adapters.in.mcp.auth;

import io.smallrye.config.ConfigSourceContext;
import io.smallrye.config.ConfigSourceFactory;
import io.smallrye.config.ConfigValue;
import io.smallrye.config.PropertiesConfigSource;
import org.eclipse.microprofile.config.spi.ConfigSource;

import java.util.List;
import java.util.OptionalInt;
import java.util.Properties;

/**
 * Derives the internal {@code quarkus.oidc.*} / HTTP permission-policy switches from the presence
 * of the two operator-facing MCP OAuth env vars (docs/adr/0026, issue 01), so
 * {@code MCP_OIDC_ISSUER}/{@code MCP_OIDC_AUDIENCE} stay the whole operator-visible surface while
 * the underlying extension and the {@code /api/mcp*} permission policy get their real property
 * names. Deliberately does NOT express this via a SmallRye {@code ${MCP_OIDC_ISSUER:}} expansion
 * of {@code quarkus.oidc.auth-server-url} in application.yml: the auth-on test profile derives
 * {@code MCP_OIDC_ISSUER} FROM {@code quarkus.oidc.auth-server-url} (Keycloak Dev Services'
 * dynamically-assigned URL), and defining the reverse mapping as a static YAML expression would
 * create a circular property reference. Producing the derived properties here, once, from the
 * already-resolved value of {@code MCP_OIDC_ISSUER}, breaks that cycle.
 */
public class McpOidcRuntimeConfigSourceFactory implements ConfigSourceFactory {

    private static final String DERIVED_SOURCE_NAME = "mcp-oidc-derived-config";
    private static final int DERIVED_SOURCE_ORDINAL = 275;

    @Override
    public Iterable<ConfigSource> getConfigSources(ConfigSourceContext context) {
        String issuer = blankToAbsent(context.getValue(McpOidcBootMode.ISSUER_ENV_VAR));
        String audience = blankToAbsent(context.getValue(McpOidcBootMode.AUDIENCE_ENV_VAR));
        boolean enforced = issuer != null && audience != null;

        Properties derived = new Properties();
        derived.setProperty("quarkus.oidc.tenant-enabled", String.valueOf(enforced));
        derived.setProperty("quarkus.http.auth.permission.mcp.policy", enforced ? "authenticated" : "permit");
        if (issuer != null) {
            derived.setProperty("quarkus.oidc.auth-server-url", issuer);
            // Bearer-only resource server: verify tokens locally via JWKS, never fall back to
            // remote introspection (which would need a confidential client secret we don't have —
            // this endpoint validates callers, it never acts as one).
            derived.setProperty("quarkus.oidc.application-type", "service");
        }
        if (audience != null) {
            derived.setProperty("quarkus.oidc.token.audience", audience);
        }

        return List.of(new PropertiesConfigSource(derived, DERIVED_SOURCE_NAME, DERIVED_SOURCE_ORDINAL));
    }

    @Override
    public OptionalInt getPriority() {
        return OptionalInt.of(0);
    }

    private static String blankToAbsent(ConfigValue value) {
        if (value == null || value.getValue() == null) {
            return null;
        }
        String trimmed = value.getValue().trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
