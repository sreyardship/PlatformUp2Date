package org.yardship.core.services;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import org.yardship.core.domain.primitives.VersionApplication;
import org.yardship.core.ports.in.ApplicationVersionPort;
import org.yardship.core.ports.out.VersionRepository;

import java.util.List;

@RequestScoped
public class ApplicationVersionService implements ApplicationVersionPort {

    @Inject
    VersionRepository versionRepository;

    @Override
    public List<VersionApplication> getApplications() {
        return versionRepository.getAllVersionApplications();
    }
}
