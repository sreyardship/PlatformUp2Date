package org.yardship.adapters.out.versionclient;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.yardship.core.domain.primitives.Version;
import org.yardship.core.ports.out.VersionRepository;

@ApplicationScoped
public class VersionClient implements VersionRepository {

    @RestClient
    private YardshipClient yardshipClient;

    public String hello() {
        return yardshipClient.getApiVersion().Version();
    }

    @Override
    public Version getCurrentVersion() {
        String versionResponse = yardshipClient.getApiVersion().Version();
        return new Version(versionResponse);
    }

    @Override
    public Version getLatestVersion() {
        return null;
    }
}
