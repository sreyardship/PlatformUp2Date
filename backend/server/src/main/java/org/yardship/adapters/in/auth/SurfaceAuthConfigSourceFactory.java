package org.yardship.adapters.in.auth;

import io.smallrye.config.ConfigSourceContext;
import io.smallrye.config.ConfigSourceFactory;
import io.smallrye.config.ConfigValue;
import io.smallrye.config.PropertiesConfigSource;
import org.eclipse.microprofile.config.spi.ConfigSource;

import java.util.List;
import java.util.OptionalInt;
import java.util.Properties;

/**
 * Derives the internal {@code quarkus.oidc.*} / HTTP permission-policy switches from the shared,
 * operator-facing auth env vars (docs/adr/0026, docs/adr/0028, issue 01) — the generalization of
 * the former MCP-only {@code McpOidcRuntimeConfigSourceFactory}. {@code OIDC_ISSUER}/
 * {@code OIDC_AUDIENCE}/{@code MCP_OIDC_ROLE} stay the whole operator-visible surface while the
 * underlying extension and each protected surface's HTTP permission policy get their real
 * property names.
 *
 * <p>Deliberately does NOT express this via a static SmallRye {@code ${OIDC_ISSUER:}} expansion
 * of {@code quarkus.oidc.auth-server-url} in application.yml: the auth-on test profile derives
 * {@code OIDC_ISSUER} FROM {@code quarkus.oidc.auth-server-url} (Keycloak Dev Services'
 * dynamically-assigned URL), and defining the reverse mapping as a static YAML expression would
 * create a circular property reference. Producing the derived properties here, once, from the
 * already-resolved values of {@code OIDC_ISSUER}/{@code OIDC_AUDIENCE}/{@code MCP_OIDC_ROLE},
 * breaks that cycle.
 *
 * <p>Reads the RAW {@code MCP_OIDC_ROLE} value (no {@code :pu2d-mcp} default in the property
 * expression) — see {@link SurfaceAuthMode#resolve}'s Javadoc for why pre-applying the default
 * here would break the auth-off default / role-without-issuer boot-failure contract.
 *
 * <p><b>Deliberately does NOT call {@link SurfaceAuthMode#resolve} (and so never throws)</b>: this
 * factory runs during Quarkus's config-source resolution, which happens at least twice — once
 * during the BUILD_TIME pass (before Keycloak Dev Services has started, so an expression like
 * {@code OIDC_ISSUER=${quarkus.oidc.auth-server-url}} in the auth-on test profile cannot resolve
 * yet, while a literal {@code MCP_OIDC_ROLE=pu2d-mcp} resolves immediately) and again at
 * RUNTIME_INIT once the real value is available. Calling the strict, throwing {@code resolve} here
 * would spuriously fail the transient BUILD_TIME pass (issuer-still-unresolved, role-already-
 * resolved looks identical to a genuine role-without-issuer misconfiguration). So this factory
 * derives properties tolerantly straight from presence, treating any incomplete/transient
 * combination as "not yet enabled" rather than an error; only {@link SurfaceAuthBootAnnouncer},
 * which resolves once at genuine application startup after all values have settled, calls
 * {@code resolve} and is the sole place that fails the boot.
 *
 * <p>MCP's permission policy moves from "any authenticated caller" ({@code authenticated}) to a
 * named ROLE policy: {@code quarkus.http.auth.policy.<surface>-role.roles-allowed=<role>} plus
 * {@code quarkus.http.auth.permission.<surface>.policy=<surface>-role}. application.yml still
 * declares {@code quarkus.http.auth.permission.mcp.paths=/api/mcp*} statically (paths are fixed
 * per surface, not derived); only the {@code .policy} property — and, when enabled, the matching
 * named role policy — is produced here.
 */
public class SurfaceAuthConfigSourceFactory implements ConfigSourceFactory {

    private static final String DERIVED_SOURCE_NAME = "surface-auth-derived-config";
    private static final int DERIVED_SOURCE_ORDINAL = 275;

    @Override
    public Iterable<ConfigSource> getConfigSources(ConfigSourceContext context) {
        String issuer = valueOf(context, SurfaceAuthMode.ISSUER_ENV_VAR);
        String audience = valueOf(context, SurfaceAuthMode.AUDIENCE_ENV_VAR);
        String mcpRole = valueOf(context, SurfaceAuthMode.MCP_ROLE_ENV_VAR);
        boolean enabled = issuer != null && audience != null;

        Properties derived = new Properties();
        if (enabled) {
            derived.setProperty("quarkus.oidc.tenant-enabled", "true");
            derived.setProperty("quarkus.oidc.auth-server-url", issuer);
            // Bearer-only resource server: verify tokens locally via JWKS, never fall back to
            // remote introspection (which would need a confidential client secret we don't have —
            // this endpoint validates callers, it never acts as one).
            derived.setProperty("quarkus.oidc.application-type", "service");
            derived.setProperty("quarkus.oidc.token.audience", audience);

            String mcpPolicyName = "mcp-role";
            String effectiveMcpRole = mcpRole != null ? mcpRole : SurfaceAuthMode.DEFAULT_MCP_ROLE;
            derived.setProperty("quarkus.http.auth.policy." + mcpPolicyName + ".roles-allowed", effectiveMcpRole);
            derived.setProperty("quarkus.http.auth.permission.mcp.policy", mcpPolicyName);
        } else {
            derived.setProperty("quarkus.oidc.tenant-enabled", "false");
            derived.setProperty("quarkus.http.auth.permission.mcp.policy", "permit");
        }

        return List.of(new PropertiesConfigSource(derived, DERIVED_SOURCE_NAME, DERIVED_SOURCE_ORDINAL));
    }

    @Override
    public OptionalInt getPriority() {
        return OptionalInt.of(0);
    }

    private static String valueOf(ConfigSourceContext context, String propertyName) {
        return blankToAbsent(context.getValue(propertyName));
    }

    private static String blankToAbsent(ConfigValue value) {
        if (value == null || value.getValue() == null) {
            return null;
        }
        String trimmed = value.getValue().trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
