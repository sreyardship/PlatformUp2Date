package org.yardship.adapters.out.versionclient;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;

/**
 * Adds {@code Authorization: Bearer <token>} to outbound requests so the scrape's
 * {@code latest} leg authenticates against the GitHub Releases API (60 req/hr -> 5,000 req/hr).
 *
 * <p><b>Scoped to the {@code latest} (GithubReleaseClient) clients only.</b> It is registered
 * exclusively on the GitHub build branch in {@link ApplicationVersionClient#buildClients()} and
 * never on the {@code current} (CurrentVersionClient) clients. The {@code current} leg hits our
 * own deployment endpoints; sending a GitHub token there would be a secret-exfiltration bug.
 *
 * <p><b>Residual assumption:</b> this trusts that {@code latest} always points at GitHub. If a
 * non-GitHub {@code latest} URL is ever configured, the token would be sent to that host. There
 * is no host check here — the assumption lives in configuration, not in this filter.
 */
public class GithubAuthFilter implements ClientRequestFilter {

    private final String token;

    GithubAuthFilter(String token) {
        this.token = token;
    }

    @Override
    public void filter(ClientRequestContext requestContext) {
        requestContext.getHeaders().putSingle(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
}
