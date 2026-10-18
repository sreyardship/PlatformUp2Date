package org.yardship.adapters.out.versionsource.auth;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Adds {@code Authorization: Basic <base64(username:password)>} to outbound requests: the
 * {@code auth.type: basic} credential of any HTTP kind on either leg (ADR-0008), and the realm
 * credential of the {@code oci-registry} bearer dance (ADR-0013). Rendered onto each GET by
 * {@link AuthorizationHeaderRenderer} for the shared {@code HttpTransport}.
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
        String credential = Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        requestContext.getHeaders().putSingle(HttpHeaders.AUTHORIZATION, "Basic " + credential);
    }
}
