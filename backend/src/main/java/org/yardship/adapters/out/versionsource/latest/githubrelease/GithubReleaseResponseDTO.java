package org.yardship.adapters.out.versionsource.latest.githubrelease;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One element of GitHub's {@code GET /repos/{owner}/{repo}/releases} array, as
 * {@link GithubReleaseVersionExtractor} reads it.
 *
 * <p>Registered for native-image reflection: this DTO is deserialized by a plain
 * {@code ObjectMapper} over the shared transport's response body, not as a REST-client return type
 * (there is no REST client since ADR-0040), so Quarkus does not auto-register it. Without
 * {@link RegisterForReflection}, Jackson cannot access its fields in a native build and every
 * {@code github-release} scrape fails — the same rule {@code TagsListDTO} follows.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class GithubReleaseResponseDTO {
    public String name;
    @JsonProperty("tag_name")
    public String tagName;
    public boolean prerelease;
    public boolean draft;
}
