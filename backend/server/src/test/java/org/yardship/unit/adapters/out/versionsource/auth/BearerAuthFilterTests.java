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
 * emits {@code Authorization: Bearer <token>}. Issue 03 generalizes the former GitHub-specific
 * {@code GithubAuthFilter} into this shared filter so both the {@code latest} leg
 * ({@code GithubReleaseLatestSource}) and the {@code http} {@code current} leg
 * ({@code HttpCurrentSourceFactory}, {@code auth.type: bearer}) can register the SAME filter class.
 * The "to whom do I send this token" boundary documentation now lives on the sources that register
 * the filter, not on the filter itself — see {@code GithubReleaseLatestSource} and
 * {@code HttpCurrentSourceFactory} Javadoc.
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
