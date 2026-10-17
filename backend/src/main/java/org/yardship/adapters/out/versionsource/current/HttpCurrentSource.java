package org.yardship.adapters.out.versionsource.current;

import org.yardship.adapters.out.versionsource.http.HttpTransport;
import org.yardship.adapters.out.versionsource.http.HttpVersionExtractor;
import org.yardship.core.domain.primitives.VersionValue;
import org.yardship.core.ports.out.CurrentVersionSource;

/**
 * The {@link CurrentVersionSource} every HTTP current-leg kind is: one shared
 * {@link HttpTransport} doing the configured GET, and the kind's own {@link HttpVersionExtractor}
 * reading a version out of the final response. {@code http-json}, {@code http-header} and
 * {@code http-prometheus} differ only in the extractor their factory picks.
 *
 * <p>A plain (non-CDI), per-app POJO. Not {@code Closeable}: the transport holds no resource
 * needing release.
 */
public final class HttpCurrentSource implements CurrentVersionSource {

    private final HttpTransport transport;
    private final HttpVersionExtractor extractor;

    public HttpCurrentSource(HttpTransport transport, HttpVersionExtractor extractor) {
        this.transport = transport;
        this.extractor = extractor;
    }

    @Override
    public VersionValue version() {
        return extractor.extract(transport.get());
    }
}
