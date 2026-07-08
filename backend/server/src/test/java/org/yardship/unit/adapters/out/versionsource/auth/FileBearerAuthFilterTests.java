package org.yardship.unit.adapters.out.versionsource.auth;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yardship.adapters.out.versionsource.auth.FileBearerAuthFilter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FileBearerAuthFilter} — the sibling of {@code BearerAuthFilter} that reads
 * the bearer token from a file on EVERY request (issue 01: token-file). A projected Kubernetes
 * serviceaccount token rotates on disk, so the filter must re-read (and trim) the file per request
 * rather than capturing the token at construction.
 *
 * <p>Mirrors {@code BearerAuthFilterTests}'s shape: {@code quarkus-junit5-mockito} is on the test
 * classpath, so {@link ClientRequestContext} is mocked rather than hand-rolled. A JUnit
 * {@link TempDir} provides the token file.
 */
class FileBearerAuthFilterTests {

    private static ClientRequestContext mockContextWithHeaders(MultivaluedMap<String, Object> headers) {
        ClientRequestContext requestContext = mock(ClientRequestContext.class);
        when(requestContext.getHeaders()).thenReturn(headers);
        return requestContext;
    }

    @Test
    void filter_setsAuthorizationHeader_toBearerTrimmedFileContents(@TempDir Path dir) throws IOException {
        Path tokenFile = dir.resolve("token");
        Files.writeString(tokenFile, "  file-tok\n");
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();

        FileBearerAuthFilter filter = new FileBearerAuthFilter(tokenFile.toString());
        filter.filter(mockContextWithHeaders(headers));

        assertEquals("Bearer file-tok", headers.getFirst(HttpHeaders.AUTHORIZATION));
    }

    @Test
    void filter_reReadsTheFile_onEachCall(@TempDir Path dir) throws IOException {
        Path tokenFile = dir.resolve("token");
        Files.writeString(tokenFile, "first-tok");

        FileBearerAuthFilter filter = new FileBearerAuthFilter(tokenFile.toString());

        MultivaluedMap<String, Object> firstHeaders = new MultivaluedHashMap<>();
        filter.filter(mockContextWithHeaders(firstHeaders));
        assertEquals("Bearer first-tok", firstHeaders.getFirst(HttpHeaders.AUTHORIZATION));

        // The projected token rotates on disk between requests; the filter must pick up the new value.
        Files.writeString(tokenFile, "second-tok");

        MultivaluedMap<String, Object> secondHeaders = new MultivaluedHashMap<>();
        filter.filter(mockContextWithHeaders(secondHeaders));
        assertEquals("Bearer second-tok", secondHeaders.getFirst(HttpHeaders.AUTHORIZATION));
    }

    @Test
    void filter_throwsNamingThePath_whenFileIsMissing(@TempDir Path dir) {
        Path missing = dir.resolve("does-not-exist");
        FileBearerAuthFilter filter = new FileBearerAuthFilter(missing.toString());

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> filter.filter(mockContextWithHeaders(new MultivaluedHashMap<>())));
        assertTrue(ex.getMessage() != null && ex.getMessage().contains(missing.toString()),
                "the exception must name the missing token-file path; was: " + ex.getMessage());
    }

    @Test
    void filter_throwsNamingThePath_whenFileIsBlankAfterTrim(@TempDir Path dir) throws IOException {
        Path blank = dir.resolve("blank-token");
        Files.writeString(blank, "   \n\t ");
        FileBearerAuthFilter filter = new FileBearerAuthFilter(blank.toString());

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> filter.filter(mockContextWithHeaders(new MultivaluedHashMap<>())));
        assertTrue(ex.getMessage() != null && ex.getMessage().contains(blank.toString()),
                "the exception must name the blank token-file path; was: " + ex.getMessage());
    }

    @Test
    void filter_throwsNamingThePath_whenFileIsUnreadable(@TempDir Path dir) throws IOException {
        // A directory at the token path is readable-as-a-path but not readable-as-a-file: reading it
        // as a token fails the same way an unreadable file does.
        Path unreadable = dir.resolve("a-directory");
        Files.createDirectory(unreadable);
        FileBearerAuthFilter filter = new FileBearerAuthFilter(unreadable.toString());

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> filter.filter(mockContextWithHeaders(new MultivaluedHashMap<>())));
        assertTrue(ex.getMessage() != null && ex.getMessage().contains(unreadable.toString()),
                "the exception must name the unreadable token-file path; was: " + ex.getMessage());
    }
}
