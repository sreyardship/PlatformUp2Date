package org.yardship.adapters.out.versionsource.latest.githubrelease;

import com.fasterxml.jackson.annotation.JsonProperty;

public class GithubReleaseResponseDTO {
    public String name;
    @JsonProperty("tag_name")
    public String tagName;
    public boolean prerelease;
    public boolean draft;
}
