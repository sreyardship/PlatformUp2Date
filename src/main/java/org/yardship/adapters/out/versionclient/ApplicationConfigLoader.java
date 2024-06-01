package org.yardship.adapters.out.versionclient;

import io.smallrye.config.ConfigMapping;

import java.net.URI;
import java.util.List;

@ConfigMapping(prefix = "platform-config")
public interface ApplicationConfigLoader {
    List<AppConfig> apps();

    interface AppConfig {
        String name();
        String current();
        String latest();
    }
}