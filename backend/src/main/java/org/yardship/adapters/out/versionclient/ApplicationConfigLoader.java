package org.yardship.adapters.out.versionclient;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;
import java.util.List;

@ConfigMapping(prefix = "platform-config")
public interface ApplicationConfigLoader {
    String scrapeInterval();
    List<AppConfig> apps();

    /**
     * Manual-scrape rolling-window budget. Defaults: at most 10 triggers per 1h sliding window.
     */
    ScrapeTrigger scrapeTrigger();

    interface AppConfig {
        String name();
        String current();
        String latest();
    }

    interface ScrapeTrigger {
        @WithDefault("10")
        int maxPerWindow();

        @WithDefault("1h")
        Duration window();
    }
}
