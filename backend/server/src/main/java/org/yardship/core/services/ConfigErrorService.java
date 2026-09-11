package org.yardship.core.services;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.yardship.core.domain.primitives.ConfigError;
import org.yardship.core.ports.in.ConfigErrorPort;
import org.yardship.core.ports.out.ConfigErrors;

import java.util.List;

/**
 * The configuration-health use case: it answers what is misconfigured, for one Application and for
 * the fleet (ADR-0035).
 *
 * <p>It forwards without reinterpreting, and that is the whole of it. A Config error is recorded
 * once, when the fleet's Version sources are assembled, and never self-heals — so there is nothing
 * here to recompute, filter or re-derive. What the use case buys is the direction of the arrow:
 * a Surface asks the core, and the core asks the driven side.
 */
@ApplicationScoped
public class ConfigErrorService implements ConfigErrorPort {

    private final ConfigErrors configErrors;

    @Inject
    public ConfigErrorService(ConfigErrors configErrors) {
        this.configErrors = configErrors;
    }

    @Override
    public List<ConfigError> configErrorsFor(String applicationName) {
        return configErrors.forApp(applicationName);
    }

    @Override
    public List<ConfigError> allConfigErrors() {
        return configErrors.all();
    }

    @Override
    public int unnamedAppCount() {
        return configErrors.unnamedAppCount();
    }
}
