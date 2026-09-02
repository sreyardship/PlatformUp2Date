package org.yardship.adapters.out.versionsource.auth;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Adds {@code Authorization: Basic <base64(username:password)>} to outbound requests so the
 * {@code http-json} current source can authenticate against an upstream that requires HTTP Basic auth
 * (see ADR-0008).
 *
 * <p><b>Residual assumption:</b> like {@link BearerAuthFilter}, this trusts that the credential
 * belongs to the configured {@code url}. There is no host check here — the credential is sent to
 * whatever url the client was built with, and that trust lives in configuration, not in this filter.
 */
public class BasicAuthFilter implements ClientRequestFilter {

    private final String username;
    private final String password;

    public BasicAuthFilter(String username, String password) {
        this.username = username;
        this.password = password;
    }

    @Override
    public void filter(ClientRequestContext requestContext) {
        requestContext.getHeaders().putSingle(HttpHeaders.AUTHORIZATION, basicHeaderValue(username, password));
    }

    /**
     * The {@code Authorization} header value for a {@code username}/{@code password} pair — shared
     * with adapters that attach the same credential outside the JAX-RS filter chain (e.g. a
     * hand-rolled redirect transport that needs the exact same header format on re-issued requests).
     */
    public static String basicHeaderValue(String username, String password) {
        String credential = Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        return "Basic " + credential;
    }
}
