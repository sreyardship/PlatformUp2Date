package org.yardship.unit.adapters.out.versionsource.auth;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.auth.BasicAuthFilter;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BasicAuthFilter} — the {@code ClientRequestFilter} that authenticates the
 * {@code http} current source's outbound requests with HTTP Basic auth (issue 02, Harbor case study:
 * {@code container-registry.sreyardship.com/api/v2.0/systeminfo} only returns {@code harbor_version}
 * when authenticated).
 *
 * <p>Mirrors {@code BearerAuthFilter}'s shape but emits {@code Authorization: Basic <base64>} instead
 * of {@code Bearer <token>}. {@code quarkus-junit5-mockito} is on the test classpath (see
 * {@code build.gradle}), so {@link ClientRequestContext} is mocked here rather than hand-rolled.
 */
class BasicAuthFilterTests {

    @Test
    void filter_setsAuthorizationHeader_toBasicBase64OfUsernameColonPassword() {
        ClientRequestContext requestContext = mock(ClientRequestContext.class);
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        when(requestContext.getHeaders()).thenReturn(headers);

        BasicAuthFilter filter = new BasicAuthFilter("alice", "s3cr3t");
        filter.filter(requestContext);

        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("alice:s3cr3t".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, headers.getFirst(HttpHeaders.AUTHORIZATION));
    }

    @Test
    void filter_encodesUsernameAndPassword_asUtf8BeforeBase64() {
        // A non-ASCII credential pins the UTF-8 encoding step explicitly (Base64 of raw bytes is
        // encoding-sensitive), rather than relying on the platform default charset.
        ClientRequestContext requestContext = mock(ClientRequestContext.class);
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        when(requestContext.getHeaders()).thenReturn(headers);

        BasicAuthFilter filter = new BasicAuthFilter("usér", "pâss");
        filter.filter(requestContext);

        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("usér:pâss".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, headers.getFirst(HttpHeaders.AUTHORIZATION));
    }
}
