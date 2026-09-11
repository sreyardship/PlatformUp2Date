package org.yardship.unit.core.services;

import org.junit.jupiter.api.Test;
import org.yardship.core.domain.primitives.ChangelogTemplate;
import org.yardship.core.domain.primitives.VersionScheme;
import org.yardship.core.ports.out.ChangelogLinks;
import org.yardship.core.services.ChangelogLinkService;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the Changelog link use case. The service is one forwarding method, so what is
 * worth pinning is exactly that it forwards: the Application name reaches the out-port, and both
 * answers — a present template and {@link Optional#empty()} — come back unreinterpreted.
 *
 * <p>Empty is not an error here (ADR-0021, ADR-0032): an Application with no {@code changelog-url}
 * and an Application whose template was illegal both project no Changelog link, and the service
 * must not tell them apart.
 */
class ChangelogLinkServiceTests {

    private static final ChangelogTemplate TEMPLATE = new ChangelogTemplate(
            "https://example.test/releases/v{version}", VersionScheme.SEMVER, Optional.empty());

    @Test
    void changelogFor_forwardsTheAppName_andReturnsThePresentTemplateUnchanged() {
        FakeChangelogLinks links = new FakeChangelogLinks(Optional.of(TEMPLATE));
        ChangelogLinkService sut = new ChangelogLinkService(links);

        Optional<ChangelogTemplate> result = sut.changelogFor("grafana");

        assertEquals("grafana", links.lastAppAsked, "the app name must reach the out-port");
        assertTrue(result.isPresent());
        assertSame(TEMPLATE, result.get(), "the service must not rebuild or reinterpret the template");
    }

    @Test
    void changelogFor_returnsEmpty_whenTheApplicationHasNoChangelogLink() {
        ChangelogLinkService sut = new ChangelogLinkService(new FakeChangelogLinks(Optional.empty()));

        assertTrue(sut.changelogFor("bad-changelog-app").isEmpty(),
                "empty must pass through as empty — no substitute, no throw");
    }

    /** A fake out-port: answers with a fixed result and records the app name it was asked about. */
    private static final class FakeChangelogLinks implements ChangelogLinks {

        private final Optional<ChangelogTemplate> answer;
        private String lastAppAsked;

        private FakeChangelogLinks(Optional<ChangelogTemplate> answer) {
            this.answer = answer;
        }

        @Override
        public Optional<ChangelogTemplate> forApp(String appName) {
            lastAppAsked = appName;
            return answer;
        }
    }
}
