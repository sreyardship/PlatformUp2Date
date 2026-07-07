package org.yardship.integration.adapter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yardship.cli.adapter.OfflineBodySource;
import org.yardship.cli.port.BodySource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Integration tests for {@link OfflineBodySource}: real filesystem and stdin I/O, no mocks. Proves
 * both {@code --body-file} and {@code -} (stdin) body acquisition paths.
 */
class OfflineBodySourceIT {

    @Test
    void fromFile_readsFullFileContentsAsUtf8(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("body.txt");
        Files.writeString(file, "Version: 1.2.3\nVersion: 2.0.0\n", StandardCharsets.UTF_8);

        OfflineBodySource source = OfflineBodySource.fromFile(file);

        assertEquals("Version: 1.2.3\nVersion: 2.0.0\n", source.body());
    }

    @Test
    void fromFile_missingFile_throwsBodyFetchException(@TempDir Path tempDir) {
        Path missing = tempDir.resolve("does-not-exist.txt");

        OfflineBodySource source = OfflineBodySource.fromFile(missing);

        assertThrows(BodySource.BodyFetchException.class, source::body);
    }

    @Test
    void fromStream_readsFullStreamContentsAsUtf8_forStdinRedirection() {
        ByteArrayInputStream stdinStandIn =
                new ByteArrayInputStream("Version: 3.4.5".getBytes(StandardCharsets.UTF_8));

        OfflineBodySource source = OfflineBodySource.fromStream(stdinStandIn);

        assertEquals("Version: 3.4.5", source.body());
    }
}
