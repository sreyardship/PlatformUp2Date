package org.yardship.unit.adapters.out.versionsource.latest.githubrelease;

import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.core.domain.primitives.VersionValue;
import org.yardship.core.ports.out.LatestVersionSource;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Architectural tripwire for the redirect implementation: redirect traversal remains an internal
 * GitHub adapter detail and does not widen the application's public/configuration/core surface.
 */
class GithubReleaseRedirectArchitectureTests {

    @Test
    void redirectTraversal_doesNotWidenExistingPublicContracts() throws NoSuchMethodException {
        assertEquals(Set.of("token", "apiBaseUrl"), githubConfigMembers(),
                "redirect policy must not add a configuration field");
        assertEquals(Set.of("version"), publicPortMethods(),
                "redirect policy must not widen the existing latest-version port");
        assertEquals(VersionValue.class, LatestVersionSource.class.getMethod("version").getReturnType(),
                "the existing latest-version port must continue returning the existing domain value");
    }

    private static Set<String> githubConfigMembers() {
        return Arrays.stream(ApplicationConfigLoader.Github.class.getDeclaredMethods())
                .map(method -> method.getName())
                .collect(Collectors.toSet());
    }

    private static Set<String> publicPortMethods() {
        return Arrays.stream(LatestVersionSource.class.getMethods())
                .map(method -> method.getName())
                .collect(Collectors.toSet());
    }
}
