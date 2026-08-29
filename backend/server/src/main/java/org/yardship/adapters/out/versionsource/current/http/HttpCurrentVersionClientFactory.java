package org.yardship.adapters.out.versionsource.current.http;

import org.yardship.adapters.out.versionsource.VersionResponseExceptionMapper;

import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.client.ClientRequestFilter;

import java.net.URI;
import java.security.KeyStore;
import java.util.Optional;

/**
 * Builds a ready {@link HttpCurrentVersionClient} for a given base URL — the only Arc-bound piece left
 * after {@code HttpCurrentSource} became a pure POJO. It is the sole boundary that knows how to
 * construct the per-hop REST clients for the {@code http} current-version kind: it applies the
 * {@link VersionResponseExceptionMapper} to final non-2xx responses and, when present, registers an
 * auth filter on the client — so call sites never touch {@code QuarkusRestClientBuilder} directly.
 * Redirect traversal is explicit in {@link RedirectingHttpCurrentVersionClient}; authorization is
 * retained only for the same effective origin, as required by ADR-0029.
 *
 * <p>The {@code authFilter} parameter lets a source register authentication (a {@link BasicAuthFilter}
 * or {@link BearerAuthFilter}) onto the current-version client; callers pass {@link Optional#empty()}
 * for the unauthenticated case.
 *
 * <p>The {@code trustStore} parameter lets a source pin a custom certificate authority onto THIS
 * client's TLS trust (a per-scraper {@code curl --cacert}: it REPLACES, not augments, the JVM default
 * bundle for this client only — never a JVM-global truststore). When present it is registered via
 * {@link QuarkusRestClientBuilder#trustStore}; callers pass {@link Optional#empty()} to keep the JVM
 * default trust bundle. The {@code HttpCurrentSourceFactory} is responsible for building the
 * {@link KeyStore} and mapping any value-level CA misconfiguration to a {@code FailedCurrentSource}
 * before calling this thin boundary.
 *
 * <p>The {@code insecureSkipTlsVerify} parameter is the {@code curl -k} escape hatch (issue 01): when
 * {@code true} it is applied via {@link QuarkusRestClientBuilder#trustAll} and
 * {@link QuarkusRestClientBuilder#verifyHost}, scoped to THIS client only — never a JVM-global trust
 * setting. Mutually exclusive with {@code trustStore} at the caller ({@code HttpCurrentSourceFactory})
 * level; this boundary does not itself enforce that.
 */
@ApplicationScoped
public class HttpCurrentVersionClientFactory {

    public HttpCurrentVersionClient build(
            String url, Optional<ClientRequestFilter> authFilter, Optional<KeyStore> trustStore,
            boolean insecureSkipTlsVerify) {
        return new RedirectingHttpCurrentVersionClient(
                URI.create(url), authFilter, trustStore, insecureSkipTlsVerify, this);
    }

    HttpCurrentVersionResponseClient buildResponseClient(
            URI target, Optional<ClientRequestFilter> authFilter, Optional<KeyStore> trustStore,
            boolean insecureSkipTlsVerify) {
        QuarkusRestClientBuilder builder = QuarkusRestClientBuilder.newBuilder()
                .baseUri(originUri(target))
                .followRedirects(false)
                .property("microprofile.rest.client.disable.default.mapper", true);
        authFilter.ifPresent(builder::register);
        trustStore.ifPresent(builder::trustStore);
        if (insecureSkipTlsVerify) {
            builder.trustAll(true);
            builder.verifyHost(false);
        }
        return builder.build(HttpCurrentVersionResponseClient.class);
    }

    private static URI originUri(URI target) {
        try {
            return new URI(target.getScheme(), target.getRawAuthority(), "/", null, null);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid current-version redirect target: " + target, e);
        }
    }
}
