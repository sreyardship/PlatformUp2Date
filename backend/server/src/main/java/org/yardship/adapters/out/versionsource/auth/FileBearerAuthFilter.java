package org.yardship.adapters.out.versionsource.auth;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Adds {@code Authorization: Bearer <token>} to outbound requests, reading the token from a file on
 * EVERY request. Sibling of {@link BearerAuthFilter}: where {@code BearerAuthFilter} carries a static
 * token captured at construction, this filter re-reads (and trims) the configured file each time
 * {@link #filter(ClientRequestContext)} is invoked — the whole point of the {@code token-file} mode
 * is that a projected Kubernetes serviceaccount token rotates on disk, so a boot-time read would
 * expire into a 401 storm.
 *
 * <p>A missing, unreadable, or blank-after-trim file at request time throws an exception naming the
 * path; that propagates out of {@code version()} and the scrape loop isolates it as a FAILED target
 * for that one app while the fleet keeps scraping.
 */
public class FileBearerAuthFilter implements ClientRequestFilter {

    private final String tokenFilePath;

    public FileBearerAuthFilter(String tokenFilePath) {
        this.tokenFilePath = tokenFilePath;
    }

    @Override
    public void filter(ClientRequestContext requestContext) {
        String token = readTrimmedToken();
        requestContext.getHeaders().putSingle(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private String readTrimmedToken() {
        String contents;
        try {
            contents = Files.readString(Path.of(tokenFilePath));
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Unable to read the bearer token-file '" + tokenFilePath + "'.", ex);
        }
        String token = contents.trim();
        if (token.isEmpty()) {
            throw new IllegalStateException(
                    "The bearer token-file '" + tokenFilePath + "' is blank.");
        }
        return token;
    }
}
