package org.yardship.adapters.out.versionsource;

import jakarta.enterprise.context.ApplicationScoped;
import org.yardship.adapters.out.versionclient.ApplicationConfigLoader;
import org.yardship.core.ports.out.CurrentVersionSource;

/**
 * Factory for the {@code http} current-version kind. Discovered as a CDI bean; validates its own
 * config fragment ({@code http} requires a non-blank {@code url}) and constructs a per-app
 * {@link HttpCurrentSource}.
 */
@ApplicationScoped
public class HttpCurrentSourceFactory implements CurrentVersionSourceFactory {

    @Override
    public String type() {
        return "http";
    }

    @Override
    public CurrentVersionSource create(ApplicationConfigLoader.VersionSource cfg) {
        String url = cfg.url()
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new IllegalArgumentException(
                        "The 'http' current source requires a non-blank 'url'."));
        return new HttpCurrentSource(url);
    }
}
