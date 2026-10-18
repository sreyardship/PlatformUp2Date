package org.yardship.unit.adapters.out.versionsource.auth;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.auth.BearerAuthFilter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BearerAuthFilter} — the scheme-generic {@code ClientRequestFilter} that
 * emits {@code Authorization: Bearer <token>}. It generalizes the GitHub-specific
 * {@code GithubAuthFilter} into this shared filter so both legs
 * ({@code GithubReleaseLatestSourceFactory}'s fleet-wide token, and any kind's
 * {@code auth.type: bearer}) render the SAME filter class onto the shared {@code HttpTransport}. The
 * "to whom do I send this token" boundary documentation lives on the factories that build the
 * filter, not on the filter itself.
 *
 * <p>Mirrors {@code BasicAuthFilterTests}'s shape: {@code quarkus-junit5-mockito} is on the test
 * classpath, so {@link ClientRequestContext} is mocked here rather than hand-rolled.
 */
class BearerAuthFilterTests {

    @Test
    void filter_setsAuthorizationHeader_toBearerToken() {
        ClientRequestContext requestContext = mock(ClientRequestContext.class);
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        when(requestContext.getHeaders()).thenReturn(headers);

        BearerAuthFilter filter = new BearerAuthFilter("tok");
        filter.filter(requestContext);

        assertEquals("Bearer tok", headers.getFirst(HttpHeaders.AUTHORIZATION));
    }
}
