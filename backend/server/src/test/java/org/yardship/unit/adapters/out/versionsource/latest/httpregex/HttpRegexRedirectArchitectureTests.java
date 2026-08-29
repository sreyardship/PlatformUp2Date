package org.yardship.unit.adapters.out.versionsource.latest.httpregex;

import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.core.domain.primitives.VersionValue;
import org.yardship.core.ports.out.LatestVersionSource;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Architectural tripwire for the text-fetch redirect implementation: redirect traversal remains an
 * internal adapter detail and does not widen the existing configuration or driven port.
 */
class HttpRegexRedirectArchitectureTests {

    @Test
    void redirectTraversal_doesNotWidenExistingConfigurationOrPort() throws NoSuchMethodException {
        assertEquals(Set.of(
                        "type", "url", "caCert", "insecureSkipTlsVerify", "repo", "registry", "regex",
                        "namespace", "workload", "container", "versionKey", "stripPrerelease", "auth", "pageSize",
                        "host", "port", "user", "privateKey", "privateKeyFile", "hostKey", "knownHosts",
                        "releaseField", "maxTags", "prereleaseFilter"),
                Arrays.stream(ApplicationConfigLoader.VersionSource.class.getDeclaredMethods())
                        .map(method -> method.getName())
                        .collect(Collectors.toSet()),
                "redirect policy must not add or remove a configuration option");
        assertEquals(Set.of("version"),
                Arrays.stream(LatestVersionSource.class.getMethods())
                        .map(method -> method.getName())
                        .collect(Collectors.toSet()),
                "redirect policy must not widen the existing latest-version port");
        assertEquals(VersionValue.class, LatestVersionSource.class.getMethod("version").getReturnType(),
                "the existing latest-version port must continue returning the existing domain value");
    }
}
