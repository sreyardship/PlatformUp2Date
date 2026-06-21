package org.yardship.unit.adapters.out.versionsource;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.VersionFetchException;
import org.yardship.adapters.out.versionsource.VersionResponseExceptionMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link VersionResponseExceptionMapper}. No HTTP, no Quarkus
 * container — the {@link Response} is mocked. The mapper only fires for non-2xx
 * responses and produces a {@link VersionFetchException} whose message carries the
 * status and a truncated body (the full body is retained on the exception).
 */
class VersionResponseExceptionMapperTests {

    private final VersionResponseExceptionMapper sut = new VersionResponseExceptionMapper();

    @Test
    void handles_returnsTrue_forNon2xxStatuses() {
        assertTrue(sut.handles(403, null));
        assertTrue(sut.handles(500, null));
        assertTrue(sut.handles(199, null));
    }

    @Test
    void handles_returnsFalse_for2xxStatuses() {
        assertFalse(sut.handles(200, null));
        assertFalse(sut.handles(204, null));
        assertFalse(sut.handles(299, null));
    }

    @Test
    void toThrowable_buildsInformativeMessageWithStatusAndBody() {
        String body = "{\"message\":\"API rate limit exceeded\"}";
        Response response = mock(Response.class);
        when(response.getStatus()).thenReturn(403);
        when(response.hasEntity()).thenReturn(true);
        when(response.readEntity(String.class)).thenReturn(body);

        RuntimeException thrown = sut.toThrowable(response);

        assertInstanceOf(VersionFetchException.class, thrown);
        VersionFetchException ex = (VersionFetchException) thrown;
        assertEquals(403, ex.status());
        assertEquals(body, ex.body());
        assertTrue(ex.getMessage().contains("403"));
        assertTrue(ex.getMessage().contains("API rate limit exceeded"));
    }

    @Test
    void toThrowable_truncatesLongBodyInMessage_butRetainsFullBodyOnException() {
        String body = "x".repeat(2000);
        Response response = mock(Response.class);
        when(response.getStatus()).thenReturn(503);
        when(response.hasEntity()).thenReturn(true);
        when(response.readEntity(String.class)).thenReturn(body);

        VersionFetchException ex = (VersionFetchException) sut.toThrowable(response);

        assertTrue(ex.getMessage().endsWith("…[truncated]"));
        assertTrue(ex.getMessage().length() < body.length());
        assertEquals(body, ex.body());
    }
}
