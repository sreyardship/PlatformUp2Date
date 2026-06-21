package org.yardship.adapters.out.versionsource.latest;

import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.core.ports.out.LatestVersionSource;

/**
 * Discovered (CDI bean) factory for one kind of {@link LatestVersionSource}.
 *
 * <p>Registered by mere existence as a CDI bean. {@link #type()} is the config {@code type}
 * discriminator this factory answers to; {@link #create(ApplicationConfigLoader.VersionSource)}
 * validates its own config fragment and constructs a per-app, plain (non-CDI) source. The auth
 * concern (e.g. registering the GitHub token filter) is owned entirely by the concrete factory and
 * source — never leaked to the resolver or the core.
 */
public interface LatestVersionSourceFactory {

    /** The config {@code type} discriminator this factory builds (e.g. {@code "github-release"}). */
    String type();

    /**
     * Build the source from this app's config fragment.
     *
     * @throws RuntimeException with a clear message if the fragment is invalid for this kind
     *                          (e.g. the {@code github-release} kind requires {@code url}).
     */
    LatestVersionSource create(ApplicationConfigLoader.VersionSource cfg);
}
