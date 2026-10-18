package org.yardship.adapters.out.versionsource.latest;

import org.yardship.adapters.out.versionsource.http.HttpTransport;
import org.yardship.adapters.out.versionsource.http.HttpVersionExtractor;
import org.yardship.core.domain.primitives.VersionValue;
import org.yardship.core.ports.out.LatestVersionSource;

/**
 * The {@link LatestVersionSource} every single-GET HTTP latest kind is: one shared
 * {@link HttpTransport} doing the configured GET, and the kind's own {@link HttpVersionExtractor}
 * reading a version out of the final response. {@code github-release} and {@code http-regex}
 * differ only in the extractor their factory picks — the latest-leg twin of
 * {@link org.yardship.adapters.out.versionsource.current.HttpCurrentSource} (ADR-0039, ADR-0040).
 * {@code oci-registry} is not one: its bearer-challenge dance and pagination need several GETs
 * per read, so it drives the transport itself.
 *
 * <p>A plain (non-CDI), per-app POJO. Not {@code Closeable}: the transport holds no resource
 * needing release.
 */
public final class HttpLatestSource implements LatestVersionSource {

    private final HttpTransport transport;
    private final HttpVersionExtractor extractor;

    public HttpLatestSource(HttpTransport transport, HttpVersionExtractor extractor) {
        this.transport = transport;
        this.extractor = extractor;
    }

    @Override
    public VersionValue version() {
        return extractor.extract(transport.get());
    }
}
