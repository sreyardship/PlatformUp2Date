package org.yardship.adapters.out.versionclient;

import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.client.ClientRequestFilter;

import java.net.URI;
import java.util.Optional;

/**
 * Builds a ready {@link CurrentVersionClient} for a given base URL — the only Arc-bound piece left
 * after {@code HttpCurrentSource} became a pure POJO. It is the sole boundary that knows how to
 * construct a REST client for the {@code http} current-version kind: it owns the
 * {@link VersionResponseExceptionMapper} registration (so a non-2xx upstream surfaces as a thrown
 * exception) and, when present, registers an auth filter on the client — so call sites never touch
 * {@code QuarkusRestClientBuilder} directly.
 *
 * <p>The {@code authFilter} parameter lets a source register authentication (a {@link BasicAuthFilter}
 * or {@link BearerAuthFilter}) onto the current-version client; callers pass {@link Optional#empty()}
 * for the unauthenticated case.
 */
@ApplicationScoped
public class CurrentVersionClientFactory {

    public CurrentVersionClient build(String url, Optional<ClientRequestFilter> authFilter) {
        QuarkusRestClientBuilder builder = QuarkusRestClientBuilder.newBuilder()
                .baseUri(URI.create(url))
                .register(VersionResponseExceptionMapper.class);
        authFilter.ifPresent(builder::register);
        return builder.build(CurrentVersionClient.class);
    }
}
