package org.yardship.core.ports.out;

import org.yardship.core.domain.primitives.VersionValue;

/**
 * A single app's source of its <em>current</em> (running/deployed) version.
 *
 * <p>A pure capability: it knows how to produce one {@link VersionValue} and nothing else. It
 * carries no config and no {@code type} discriminator — those are driven-side composition
 * concerns owned by the factory that constructs it. Per-app failure isolation lives in the
 * scrape loop, so an implementation is free to throw on an unreachable or unparseable upstream.
 */
public interface CurrentVersionSource {

    VersionValue version();
}
