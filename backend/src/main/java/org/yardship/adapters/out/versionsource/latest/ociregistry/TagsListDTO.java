package org.yardship.adapters.out.versionsource.latest.ociregistry;

import java.util.List;

/**
 * DTO for the OCI Distribution Spec {@code GET /v2/{repo}/tags/list} response.
 * Shape: {@code { "name": "library/nginx", "tags": ["1.0.0", "latest", ...] }}.
 */
public class TagsListDTO {
    public String name;
    public List<String> tags;
}
