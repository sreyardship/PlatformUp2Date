package org.yardship.adapters.out.versionclient;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@ConfigMapping(prefix = "platform-config")
public interface ApplicationConfigLoader {
    String scrapeInterval();
    List<AppConfig> apps();

    /**
     * Manual-scrape rolling-window budget. Defaults: at most 10 triggers per 1h sliding window.
     */
    ScrapeTrigger scrapeTrigger();

    /**
     * Optional GitHub authentication for the scrape's {@code latest} leg. Absent/unset leaves
     * the scrape unauthenticated (60 req/hr); present raises the limit to 5,000 req/hr.
     */
    Github github();

    interface AppConfig {
        String name();
        VersionSource current();
        VersionSource latest();
    }

    /**
     * Tagged version source: a {@code type} discriminator plus the union of type-specific fields.
     * Today only the {@code url} of the {@code http} (current) and {@code github-release} (latest)
     * types is read; the {@code namespace}/{@code workload}/{@code container} fields are reserved
     * for the Kubernetes source added in a later slice and are absent for http/github apps.
     */
    interface VersionSource {
        String type();
        Optional<String> url();
        Optional<String> namespace();
        Optional<String> workload();
        Optional<String> container();
    }

    interface ScrapeTrigger {
        @WithDefault("10")
        int maxPerWindow();

        @WithDefault("1h")
        Duration window();
    }

    interface Github {
        /**
         * Optional GitHub token. Resolved from the environment via SmallRye expansion
         * (e.g. {@code ${GITHUB_TOKEN:}}), so an unset variable yields an empty/absent
         * token rather than a startup failure.
         */
        Optional<String> token();
    }
}
