package org.yardship.core.services;

import jakarta.enterprise.context.ApplicationScoped;
import org.yardship.core.domain.primitives.Version;
import org.yardship.core.ports.in.VersionPort;
import org.yardship.core.ports.out.VersionRepository;

@ApplicationScoped
public class VersionService implements VersionPort {

    private final VersionRepository versionRepository;

    public VersionService(VersionRepository versionRepository) {
        this.versionRepository = versionRepository;
    }

    @Override
    public Version getCurrentVersion() {
        return versionRepository.getCurrentVersion();
    }

    @Override
    public Version getLatestVersion() {
        return versionRepository.getLatestVersion();
    }
}
