package org.yardship.unit.adapters.out.versionsource.current.http;

import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.adapters.out.versionsource.VersionParsers;
import org.yardship.adapters.out.versionsource.VersionSourceResolver;
import org.yardship.core.domain.primitives.VersionValue;
import org.yardship.core.ports.out.CurrentVersionSource;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Architectural tripwire for current-source redirects: the policy stays inside the outbound
 * adapter and does not widen configuration, domain ports, or the composition root.
 */
class HttpCurrentRedirectArchitectureTests {

    @Test
    void redirectTraversal_doesNotWidenExistingConfigDomainPortOrCompositionRootContracts()
            throws NoSuchMethodException {
        assertEquals(Set.of(
                        "type", "url", "caCert", "insecureSkipTlsVerify", "repo", "registry", "regex",
                        "namespace", "workload", "container", "versionKey", "stripPrerelease", "auth",
                        "pageSize", "host", "port", "user", "privateKey", "privateKeyFile", "hostKey",
                        "knownHosts", "releaseField", "maxTags", "prereleaseFilter"),
                declaredMethodNames(ApplicationConfigLoader.VersionSource.class),
                "redirect policy must not add a configuration field");
        assertEquals(Set.of("type", "username", "password", "token", "tokenFile"),
                declaredMethodNames(ApplicationConfigLoader.VersionSource.Auth.class),
                "redirect policy must not add an authentication configuration field");
        assertEquals(Set.of("version"), declaredMethodNames(CurrentVersionSource.class),
                "redirect policy must not widen the existing current-version port");
        assertEquals(VersionValue.class, CurrentVersionSource.class.getMethod("version").getReturnType(),
                "the existing current-version port must continue returning the existing domain value");
        assertEquals(Set.of(
                        List.of(Instance.class, Instance.class, ApplicationConfigLoader.class, VersionParsers.class),
                        List.of(Collection.class, Collection.class, List.class, VersionParsers.class)),
                resolverConstructors(),
                "redirect policy must not add a composition-root collaborator or constructor");
    }

    private static Set<String> declaredMethodNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .map(method -> method.getName())
                .collect(Collectors.toSet());
    }

    private static Set<List<Class<?>>> resolverConstructors() {
        return Arrays.stream(VersionSourceResolver.class.getConstructors())
                .map(Constructor::getParameterTypes)
                .map(types -> Arrays.asList(types))
                .collect(Collectors.toSet());
    }
}
