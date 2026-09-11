package org.yardship.core.services;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.yardship.core.domain.primitives.ChangelogTemplate;
import org.yardship.core.ports.in.ChangelogLinkPort;
import org.yardship.core.ports.out.ChangelogLinks;

import java.util.Optional;

/**
 * The Changelog link use case: which link, if any, an Application projects (ADR-0021, ADR-0035).
 *
 * <p>One method, and it forwards. That thinness is deliberate and is the accepted price of keeping
 * Changelog link and Config error as two terms in the code, as {@code CONTEXT.md} keeps them as two
 * terms in the language. Folding it into {@code ConfigErrorService} would buy nothing but make one
 * port mean two things.
 */
@ApplicationScoped
public class ChangelogLinkService implements ChangelogLinkPort {

    private final ChangelogLinks changelogLinks;

    @Inject
    public ChangelogLinkService(ChangelogLinks changelogLinks) {
        this.changelogLinks = changelogLinks;
    }

    @Override
    public Optional<ChangelogTemplate> changelogFor(String applicationName) {
        return changelogLinks.forApp(applicationName);
    }
}
