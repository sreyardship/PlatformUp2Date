package org.yardship.adapters.out.versionclient;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.yardship.core.domain.primitives.Version;
import org.yardship.core.ports.out.VersionRepository;

@ApplicationScoped
public class VersionClient implements VersionRepository {

    @RestClient
    private YardshipClient yardshipClient;

    @Override
    public Version getCurrentVersion() {
        VersionResponseDTO response = yardshipClient.getApiVersion();
        throwIfNull(response);

        return new Version(response.Version());
    }

    @Override
    public Version getLatestVersion() {
        return new Version("4.20.69"); // Hard coded for now..
    }

    private void throwIfNull(VersionResponseDTO input) {
        if (input == null) {
            throw new InvalidVersionResponseException("Version Response was null");
        }
    }
}
