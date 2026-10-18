package org.yardship.unit.adapters.out.versionsource.latest.githubrelease;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.latest.githubrelease.GithubReleaseResponseDTO;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the native-image reflection registration of {@link GithubReleaseResponseDTO}.
 *
 * <p>Until ADR-0040 the DTO was the return type of a REST-client interface, which Quarkus
 * registers for reflection automatically. It is now deserialized by a plain {@code ObjectMapper}
 * over the shared transport's response body, so nothing registers it but its own
 * {@link RegisterForReflection}; without it Jackson cannot access its fields in a native build and
 * every {@code github-release} scrape fails at runtime — which is how the native integration test
 * caught the omission. JVM-mode tests cannot: reflection is unconditionally available there, hence
 * this structural assertion on the annotation itself, mirroring {@code TagsListDTOReflectionTests}.
 */
class GithubReleaseResponseDTOReflectionTests {

    @Test
    void githubReleaseResponseDTO_isRegisteredForReflection_forNativeImage() {
        assertTrue(GithubReleaseResponseDTO.class.isAnnotationPresent(RegisterForReflection.class),
                "GithubReleaseResponseDTO is deserialized reflectively by Jackson and is no longer a "
                + "REST-client return type, so it must carry @RegisterForReflection to deserialize in "
                + "a native image. Without it, every github-release scrape fails at runtime.");
    }
}
