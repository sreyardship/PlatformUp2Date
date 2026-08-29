package org.yardship.documentation;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Documentation-level check for credential-owning source and factory Javadocs.
 */
@Tag("documentation")
class RedirectDocumentationTests {

    @Test
    void credentialOwningSourceAndFactoryJavadocs_referenceSafeRedirectPolicy() throws IOException {
        String githubSourceJavadoc = classJavadoc(
                "org/yardship/adapters/out/versionsource/latest/githubrelease/GithubReleaseLatestSource.java");
        String currentFactoryJavadoc = classJavadoc(
                "org/yardship/adapters/out/versionsource/current/http/HttpCurrentSourceFactory.java");

        assertTrue(githubSourceJavadoc.contains("ADR-0029"),
                "the GitHub source Javadoc must point to the shared redirect-authorization decision");
        assertTrue(githubSourceJavadoc.contains("same effective"),
                "the GitHub source Javadoc must describe same-origin credential retention");
        assertTrue(githubSourceJavadoc.contains("cross-origin target"),
                "the GitHub source Javadoc must describe credential removal for cross-origin targets");
        assertFalse(githubSourceJavadoc.contains("Residual assumption"),
                "the GitHub source Javadoc must not describe cross-host credential replay as accepted");
        assertTrue(currentFactoryJavadoc.contains("ADR-0029"),
                "the current HTTP factory Javadoc must point to the shared redirect-authorization decision");
        assertTrue(currentFactoryJavadoc.contains("removes authorization whenever"),
                "the current HTTP factory Javadoc must describe credential removal on origin changes");
        assertFalse(currentFactoryJavadoc.contains("assumption that the credential belongs"),
                "the current HTTP factory Javadoc must not describe cross-host credential replay as accepted");
    }

    private static String classJavadoc(String resourcePath) throws IOException {
        try (InputStream stream = RedirectDocumentationTests.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IOException("Missing production source resource: " + resourcePath);
            }
            String source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            int start = source.indexOf("/**");
            int end = source.indexOf("*/", start + 3);
            if (start < 0 || end < 0) {
                throw new IOException("Missing class Javadoc in production source: " + resourcePath);
            }
            return source.substring(start, end + 2);
        }
    }
}
