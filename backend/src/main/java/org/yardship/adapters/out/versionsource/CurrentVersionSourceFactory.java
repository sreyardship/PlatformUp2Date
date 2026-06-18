package org.yardship.adapters.out.versionsource;

import org.yardship.adapters.out.versionclient.ApplicationConfigLoader;
import org.yardship.core.ports.out.CurrentVersionSource;

/**
 * Discovered (CDI bean) factory for one kind of {@link CurrentVersionSource}.
 *
 * <p>Registered by mere existence as a CDI bean. {@link #type()} is the config {@code type}
 * discriminator this factory answers to; {@link #create(ApplicationConfigLoader.VersionSource)}
 * validates its own config fragment and constructs a per-app, plain (non-CDI) source. Adding a
 * source kind is a new factory class and nothing else — open-closed holds.
 */
public interface CurrentVersionSourceFactory {

    /** The config {@code type} discriminator this factory builds (e.g. {@code "http"}). */
    String type();

    /**
     * Build the source from this app's config fragment.
     *
     * @throws RuntimeException with a clear message if the fragment is invalid for this kind
     *                          (e.g. the {@code http} kind requires {@code url}).
     */
    CurrentVersionSource create(ApplicationConfigLoader.VersionSource cfg);
}
