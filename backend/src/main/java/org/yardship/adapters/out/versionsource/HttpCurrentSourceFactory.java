package org.yardship.adapters.out.versionsource;

import com.fasterxml.jackson.core.JsonPointer;
import jakarta.enterprise.context.ApplicationScoped;
import org.yardship.adapters.out.versionclient.ApplicationConfigLoader;
import org.yardship.core.ports.out.CurrentVersionSource;

/**
 * Factory for the {@code http} current-version kind. Discovered as a CDI bean; validates its own
 * config fragment ({@code http} requires a non-blank {@code url}, and {@code version-key} — if
 * present — must be a syntactically valid JSON Pointer) and constructs a per-app
 * {@link HttpCurrentSource}.
 */
@ApplicationScoped
public class HttpCurrentSourceFactory implements CurrentVersionSourceFactory {

    private static final String DEFAULT_VERSION_KEY = "/version";

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
        String versionKey = cfg.versionKey().orElse(DEFAULT_VERSION_KEY);
        validatePointerSyntax(versionKey);
        boolean stripPrerelease = cfg.stripPrerelease().orElse(false);
        return new HttpCurrentSource(url, versionKey, stripPrerelease);
    }

    private static void validatePointerSyntax(String versionKey) {
        try {
            JsonPointer.compile(versionKey);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "The 'http' current source's 'version-key' is not a valid JSON Pointer: '"
                            + versionKey + "'.", ex);
        }
    }
}
