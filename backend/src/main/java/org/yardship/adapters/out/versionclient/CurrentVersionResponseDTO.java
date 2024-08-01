package org.yardship.adapters.out.versionclient;

import com.fasterxml.jackson.annotation.JsonAlias;

public class CurrentVersionResponseDTO {

    @JsonAlias({"Version", "version"})
    public String version;
}