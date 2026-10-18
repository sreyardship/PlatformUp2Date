package org.yardship.adapters.out.versionsource.auth;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;

/**
 * Adds {@code Authorization: Bearer <token>} to outbound requests.
 *
 * <p>Scheme-generic: this filter knows nothing about which source renders it or which host the
 * request is bound for. The factory that builds it owns to whom the credential is sent — the
 * {@code auth.type: bearer} credential of any HTTP kind on either leg, the fleet-wide
 * {@code github.token} of {@code github-release}, and the token the {@code oci-registry} dance mints
 * (ADR-0013) all travel as this filter, rendered onto each GET by {@link AuthorizationHeaderRenderer}
 * for the shared {@code HttpTransport}.
 */
public class BearerAuthFilter implements ClientRequestFilter {

    private final String token;

    public BearerAuthFilter(String token) {
        this.token = token;
    }

    @Override
    public void filter(ClientRequestContext requestContext) {
        requestContext.getHeaders().putSingle(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
}
