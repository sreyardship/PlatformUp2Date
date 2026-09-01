package org.yardship.adapters.out.versionsource.current.http;
import org.yardship.adapters.out.versionsource.VersionResponseExceptionMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.client.ClientRequestFilter;

import java.security.KeyStore;
import java.util.Optional;

/**
 * Builds a ready {@link HttpCurrentVersionClient} for a given base URL — the only Arc-bound piece left
 * after {@code HttpCurrentSource} became a pure POJO. It is the sole boundary that knows how to
 * construct the transport for the {@code http} current-version kind: it owns the
 * {@link VersionResponseExceptionMapper} usage (so a non-2xx upstream surfaces as a thrown
 * exception), the optional auth filter, the optional truststore, and insecure-TLS — so call sites
 * never touch the underlying transport directly.
 *
 * <p>Per ADR-0029, the returned client follows 301/302/303/307/308 redirects on a bounded chain via
 * {@link org.yardship.adapters.out.versionsource.http.RedirectFollowingHttpGet}, retaining the
 * rendered {@code Authorization} header only same-origin, refusing an HTTPS-to-HTTP downgrade before
 * contacting the target, and applying the SAME per-client TLS configuration (truststore or
 * insecure-skip) to every hop — not just the initial request. See
 * {@link RedirectFollowingHttpCurrentVersionTransport} for the transport itself.
 *
 * <p>The {@code authFilter} parameter lets a source register authentication (a {@link BasicAuthFilter}
 * or {@link BearerAuthFilter}) for the current-version client; callers pass {@link Optional#empty()}
 * for the unauthenticated case. The filter is never registered on a JAX-RS client chain — it is
 * invoked directly against a minimal header-capturing context on every request, so its rendered
 * {@code Authorization} value can be handed to the redirect-following GET transport, which needs a
 * plain header map rather than a filter chain. This preserves each filter's exact header format
 * (including {@code FileBearerAuthFilter}'s per-request file re-read) without any global filter
 * registration.
 *
 * <p>The {@code trustStore} parameter lets a source pin a custom certificate authority onto THIS
 * client's TLS trust (a per-scraper {@code curl --cacert}: it REPLACES, not augments, the JVM default
 * bundle for this client only — never a JVM-global truststore). Callers pass {@link Optional#empty()}
 * to keep the JVM default trust bundle. The {@code HttpCurrentSourceFactory} is responsible for
 * building the {@link KeyStore} and mapping any value-level CA misconfiguration to a
 * {@code FailedCurrentSource} before calling this thin boundary.
 *
 * <p>The {@code insecureSkipTlsVerify} parameter is the {@code curl -k} escape hatch (issue 01):
 * scoped to THIS client only — never a JVM-global trust setting. Mutually exclusive with
 * {@code trustStore} at the caller ({@code HttpCurrentSourceFactory}) level; this boundary does not
 * itself enforce that.
 */
@ApplicationScoped
public class HttpCurrentVersionClientFactory {

    public HttpCurrentVersionClient build(
            String url, Optional<ClientRequestFilter> authFilter, Optional<KeyStore> trustStore,
            boolean insecureSkipTlsVerify) {
        RedirectFollowingHttpCurrentVersionTransport transport = new RedirectFollowingHttpCurrentVersionTransport(
                url, authFilter, trustStore, insecureSkipTlsVerify);
        // A method reference, not a class `implements HttpCurrentVersionClient` — that interface
        // carries @Path, and a real (build-time-indexed) class implementing it risks being swept up
        // by Quarkus's JAX-RS/CDI scanning as a resource/bean. A method reference is a synthetic
        // lambda class, invisible to that scanning (same rationale as the `latest` leg's
        // RedirectFollowingGithubReleaseClient).
        return transport::getCurrentVersion;
    }
}
