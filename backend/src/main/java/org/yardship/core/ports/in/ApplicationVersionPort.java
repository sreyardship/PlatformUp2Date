package org.yardship.core.ports.in;

import org.yardship.core.domain.primitives.VersionApplication;

import java.net.URISyntaxException;
import java.util.List;

public interface ApplicationVersionPort {
    List<VersionApplication> getApplications() throws URISyntaxException;
}
