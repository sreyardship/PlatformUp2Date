package org.yardship.core.ports.out;

import org.yardship.core.domain.primitives.Version;

public interface VersionRepository {
    Version getCurrentVersion();
    Version getLatestVersion();
}
