package org.yardship.adapters.out.versionsource.latest.ociregistry;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * DTO for the OCI Distribution Spec {@code GET /v2/{repo}/tags/list} response.
 * Shape: {@code { "name": "library/nginx", "tags": ["1.0.0", "latest", ...] }}.
 *
 * <p>Registered for native-image reflection: this DTO is deserialized via
 * {@code Response.readEntity(TagsListDTO.class)} rather than being a REST-client return type, so
 * Quarkus does not auto-register it. Without {@link RegisterForReflection}, Jackson cannot access
 * its fields in a native build and every OCI-registry scrape fails.
 */
@RegisterForReflection
public class TagsListDTO {
    public String name;
    public List<String> tags;
}
