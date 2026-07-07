package org.yardship.cli.adapter;

import org.yardship.cli.port.BodySource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Driven {@link BodySource} adapter that reads a body offline — from a local file
 * ({@code --body-file}) or from stdin ({@code -}) — with no network access. An unreadable file or
 * stdin I/O error is translated to {@link BodySource.BodyFetchException}.
 */
public final class OfflineBodySource implements BodySource {

    private final java.util.function.Supplier<String> bodyReader;

    private OfflineBodySource(java.util.function.Supplier<String> bodyReader) {
        this.bodyReader = bodyReader;
    }

    /** Reads the body from {@code path} in full, as UTF-8 text, deferred until {@link #body()} is called. */
    public static OfflineBodySource fromFile(Path path) {
        return new OfflineBodySource(() -> {
            try {
                return Files.readString(path, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new BodyFetchException("Failed to read body file '" + path + "': " + e.getMessage(), e);
            }
        });
    }

    /**
     * Reads the body from {@code in} in full (to EOF), as UTF-8 text, deferred until {@link #body()}
     * is called. Intended for stdin ({@code -}).
     */
    public static OfflineBodySource fromStream(InputStream in) {
        return new OfflineBodySource(() -> {
            try {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new BodyFetchException("Failed to read body from stream: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public String body() {
        return bodyReader.get();
    }
}
