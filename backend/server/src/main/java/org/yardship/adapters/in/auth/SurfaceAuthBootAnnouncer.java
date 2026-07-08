package org.yardship.adapters.in.auth;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * CDI startup observer for the shared-issuer, role-gated auth boot-mode contract (docs/adr/0026,
 * docs/adr/0028, issue 01) — the generalization of the former MCP-only
 * {@code McpOidcBootModeAnnouncer}. Resolves {@link SurfaceAuthMode} from the operator-facing env
 * vars and logs the single unambiguous mode line on every boot, so a typo'd variable name is
 * visible on first boot. Resolution throws {@link SurfaceAuthConfigurationException} for either
 * illegal state (issuer-without-audience, role-without-issuer), which propagates out of this
 * observer and fails application startup (never degrades to a warning).
 *
 * <p>Injects the RAW {@link SurfaceAuthMode#MCP_ROLE_ENV_VAR} value (no {@code :pu2d-mcp} default
 * in the {@code @ConfigProperty} expression) and passes it straight into {@link
 * SurfaceAuthMode#resolve} — see that method's Javadoc for why pre-applying the default here would
 * break the auth-off default / role-without-issuer boot-failure contract.
 *
 * <p>The mirror-image case — {@link SurfaceAuthMode#AUDIENCE_ENV_VAR} configured without
 * {@link SurfaceAuthMode#ISSUER_ENV_VAR} — resolves to {@link SurfaceAuthMode.Disabled} rather
 * than failing boot (there is nothing to validate the audience against without an issuer), but is
 * still surfaced as a startup warning naming {@link SurfaceAuthMode#ISSUER_ENV_VAR}, since it is
 * probably a missing or typo'd variable rather than an intentional configuration.
 */
@ApplicationScoped
public class SurfaceAuthBootAnnouncer {

    private static final Logger LOG = Logger.getLogger(SurfaceAuthBootAnnouncer.class);

    void onStart(@Observes StartupEvent event,
            @ConfigProperty(name = SurfaceAuthMode.ISSUER_ENV_VAR) Optional<String> issuer,
            @ConfigProperty(name = SurfaceAuthMode.AUDIENCE_ENV_VAR) Optional<String> audience,
            @ConfigProperty(name = SurfaceAuthMode.MCP_ROLE_ENV_VAR) Optional<String> mcpRole) {
        SurfaceAuthMode mode = SurfaceAuthMode.resolve(issuer, audience, mcpRole);
        LOG.info(mode.logMessage());
        warnIfAudienceConfiguredWithoutIssuer(issuer, audience);
    }

    private void warnIfAudienceConfiguredWithoutIssuer(Optional<String> issuer, Optional<String> audience) {
        if (isBlankOrAbsent(issuer) && !isBlankOrAbsent(audience)) {
            LOG.warn(SurfaceAuthMode.AUDIENCE_ENV_VAR + " is set but " + SurfaceAuthMode.ISSUER_ENV_VAR
                    + " is not — surface authentication stays disabled; check for a missing or typo'd "
                    + SurfaceAuthMode.ISSUER_ENV_VAR + ".");
        }
    }

    private boolean isBlankOrAbsent(Optional<String> value) {
        return value.map(String::trim).filter(trimmed -> !trimmed.isEmpty()).isEmpty();
    }
}
