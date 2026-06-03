package org.yardship.adapters.out.versionclient;

import io.smallrye.config.ConfigMapping;

import java.util.List;

@ConfigMapping(prefix = "platform-config")
public interface ApplicationConfigLoader {
    String scrapeInterval();
    List<AppConfig> apps();

    interface AppConfig {
        String name();
        String current();
        String latest();
    }
}