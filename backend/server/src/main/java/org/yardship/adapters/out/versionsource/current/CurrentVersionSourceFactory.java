package org.yardship.adapters.out.versionsource.current;

import org.yardship.adapters.out.versionsource.ApplicationConfigLoader;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.ports.out.CurrentVersionSource;

/**
 * Discovered (CDI bean) factory for one kind of {@link CurrentVersionSource}.
 *
 * <p>Registered by mere existence as a CDI bean. {@link #type()} is the config {@code type}
 * discriminator this factory answers to;
 * {@link #create(ApplicationConfigLoader.VersionSource, VersionParser)} validates its own config
 * fragment and constructs a per-app, plain (non-CDI) source, handing it the app's
 * {@link VersionParser} so the source produces {@code VersionValue} in the app's scheme. Adding a
 * source kind is a new factory class and nothing else — open-closed holds.
 */
public interface CurrentVersionSourceFactory {

    /** The config {@code type} discriminator this factory builds (e.g. {@code "http"}). */
    String type();

    /**
     * Build the source from this app's config fragment, threading in the app's {@link VersionParser}.
     *
     * @param cfg    this app's version-source config fragment
     * @param parser the single per-app parser shared by both legs, so current and latest are always
     *               commensurable; the source uses it instead of constructing versions directly
     * @throws RuntimeException with a clear message if the fragment is invalid for this kind
     *                          (e.g. the {@code http} kind requires {@code url}).
     */
    CurrentVersionSource create(ApplicationConfigLoader.VersionSource cfg, VersionParser parser);
}
