package org.yardship.confcheck.adapter;

import org.yardship.confcheck.port.ResponseSource;

import java.util.List;
import java.util.Map;

/**
 * Driven {@link ResponseSource} adapter that returns a fixture status code and header map, with no
 * network access — the {@code header} surface's equivalent of {@link OfflineBodySource}. Built
 * directly from values the {@code header} command's own options supply ({@code --status} and
 * repeatable {@code --header-value NAME=VALUE}), so unlike {@link OfflineBodySource} it performs no
 * I/O of its own and needs no deferred-read wiring.
 */
public final class OfflineResponseSource implements ResponseSource {

    private final Response fixture;

    private OfflineResponseSource(Response fixture) {
        this.fixture = fixture;
    }

    public static OfflineResponseSource of(int statusCode, Map<String, List<String>> headers) {
        return new OfflineResponseSource(new Response(statusCode, headers));
    }

    @Override
    public Response fetch() {
        return fixture;
    }
}
