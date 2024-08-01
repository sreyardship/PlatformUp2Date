package org.yardship.core.ports.out;

import org.yardship.core.domain.primitives.VersionApplication;

import java.util.List;

public interface VersionRepository {
    List<VersionApplication> getAllVersionApplications();
}
