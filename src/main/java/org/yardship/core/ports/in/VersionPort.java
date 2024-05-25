package org.yardship.core.ports.in;

import org.yardship.core.domain.primitives.Version;

public interface VersionPort {
    Version getCurrentVersion(); // Just for showcase.. will probably remove
    boolean isVersionOld();
}
