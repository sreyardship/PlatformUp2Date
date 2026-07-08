package org.yardship.adapters.in.mcp.auth;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * CDI startup observer for the MCP OAuth boot-mode contract (docs/adr/0026, issue 01). Resolves
 * {@link McpOidcBootMode} from the two operator-facing env vars and logs the single unambiguous
 * mode line on every boot, so a typo'd variable name is visible on first boot. Resolution throws
 * {@link McpOidcConfigurationException} for the issuer-without-audience illegal state, which
 * propagates out of this observer and fails application startup (never degrades to a warning).
 *
 * <p>The mirror-image case — {@link McpOidcBootMode#AUDIENCE_ENV_VAR} configured without
 * {@link McpOidcBootMode#ISSUER_ENV_VAR} — resolves to {@link McpOidcBootMode.Disabled} rather
 * than failing boot (there is nothing to validate the audience against without an issuer), but is
 * still surfaced as a startup warning naming {@link McpOidcBootMode#ISSUER_ENV_VAR}, since it is
 * probably a missing or typo'd variable rather than an intentional configuration.
 */
@ApplicationScoped
public class McpOidcBootModeAnnouncer {

    private static final Logger LOG = Logger.getLogger(McpOidcBootModeAnnouncer.class);

    void onStart(@Observes StartupEvent event,
            @ConfigProperty(name = McpOidcBootMode.ISSUER_ENV_VAR) Optional<String> issuer,
            @ConfigProperty(name = McpOidcBootMode.AUDIENCE_ENV_VAR) Optional<String> audience) {
        McpOidcBootMode mode = McpOidcBootMode.resolve(issuer, audience);
        LOG.info(mode.logMessage());
        warnIfAudienceConfiguredWithoutIssuer(issuer, audience);
    }

    private void warnIfAudienceConfiguredWithoutIssuer(Optional<String> issuer, Optional<String> audience) {
        if (isBlankOrAbsent(issuer) && !isBlankOrAbsent(audience)) {
            LOG.warn(McpOidcBootMode.AUDIENCE_ENV_VAR + " is set but " + McpOidcBootMode.ISSUER_ENV_VAR
                    + " is not — MCP OAuth enforcement stays disabled; check for a missing or typo'd "
                    + McpOidcBootMode.ISSUER_ENV_VAR + ".");
        }
    }

    private boolean isBlankOrAbsent(Optional<String> value) {
        return value.map(String::trim).filter(trimmed -> !trimmed.isEmpty()).isEmpty();
    }
}
