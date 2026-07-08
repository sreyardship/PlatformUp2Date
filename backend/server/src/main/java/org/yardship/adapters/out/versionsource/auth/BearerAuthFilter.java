package org.yardship.adapters.out.versionsource.auth;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;

/**
 * Adds {@code Authorization: Bearer <token>} to outbound requests.
 *
 * <p>Scheme-generic: this filter knows nothing about which source registers it or which host the
 * request is bound for. The source that registers it owns to whom credentials are sent — see
 * {@link org.yardship.adapters.out.versionsource.latest.githubrelease.GithubReleaseLatestSource} (the {@code latest}
 * leg) and {@code HttpCurrentSourceFactory} (the {@code current} leg, {@code auth.type: bearer}).
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
