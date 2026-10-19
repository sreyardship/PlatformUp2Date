package org.yardship.unit.adapters.out.versionsource.latest.ociregistry;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.latest.ociregistry.TagsListDTO;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the native-image reflection registration of {@link TagsListDTO}.
 *
 * <p>{@code TagsListDTO} is deserialized by a plain {@code ObjectMapper} over the shared transport's
 * response body rather than as a REST-client interface return type, so Quarkus does NOT
 * auto-register it for native-image reflection; without an explicit {@link RegisterForReflection}
 * annotation Jackson cannot access its fields in a native build and every OCI-registry scrape fails
 * at runtime. {@code GithubReleaseResponseDTOReflectionTests} guards the same rule for the
 * {@code github-release} kind.
 *
 * <p>The {@code @QuarkusTest} integration tests run in JVM mode, where reflection is unconditionally
 * available, so they cannot catch this regression — hence this structural assertion on the
 * annotation itself.
 */
class TagsListDTOReflectionTests {

    @Test
    void tagsListDTO_isRegisteredForReflection_forNativeImage() {
        assertTrue(TagsListDTO.class.isAnnotationPresent(RegisterForReflection.class),
                "TagsListDTO is deserialized reflectively by Jackson and is not a REST-client return "
                + "type, so it must carry @RegisterForReflection to deserialize in a native image. "
                + "Without it, every OCI-registry scrape fails at runtime.");
    }
}
