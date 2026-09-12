package org.yardship.unit.adapters.out.versionsource.auth;

import jakarta.ws.rs.client.ClientRequestFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yardship.adapters.out.versionsource.auth.AuthorizationHeaderRenderer;
import org.yardship.adapters.out.versionsource.auth.BasicAuthFilter;
import org.yardship.adapters.out.versionsource.auth.BearerAuthFilter;
import org.yardship.adapters.out.versionsource.auth.FileBearerAuthFilter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AuthorizationHeaderRenderer}, the single home for turning a
 * {@link ClientRequestFilter} into a literal {@code Authorization} header value. Both current-leg
 * HTTP transports ({@code http} and {@code http-header}) talk over a plain {@code java.net.http}
 * client, which needs a header map rather than a JAX-RS filter chain, so both render through here.
 *
 * <p>The file re-read case below is the load-bearing one. {@link FileBearerAuthFilter} exists
 * because a projected Kubernetes serviceaccount token rotates on disk, so a value captured once
 * would expire into a 401 storm. {@link FileBearerAuthFilterTests} covers the filter's own re-read;
 * this covers it <b>through the renderer</b>, which is the composition the renderer's Javadoc makes
 * a promise about and the one thing this extraction could silently break — memoising a rendered
 * value here would leave every other test in the suite green.
 */
class AuthorizationHeaderRendererTests {

    @Test
    void render_aBasicAuthFilter_yieldsTheBase64CredentialValue() {
        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("jenkins-bot:s3cr3t".getBytes(UTF_8));

        Optional<String> rendered =
                AuthorizationHeaderRenderer.render(new BasicAuthFilter("jenkins-bot", "s3cr3t"));

        assertEquals(Optional.of(expected), rendered);
    }

    @Test
    void render_aBearerAuthFilter_yieldsTheBearerValue() {
        Optional<String> rendered =
                AuthorizationHeaderRenderer.render(new BearerAuthFilter("gh-token"));

        assertEquals(Optional.of("Bearer gh-token"), rendered);
    }

    @Test
    void render_aFilterThatSetsNoAuthorizationHeader_yieldsEmpty() {
        ClientRequestFilter setsNothing = requestContext -> {
        };

        assertEquals(Optional.empty(), AuthorizationHeaderRenderer.render(setsNothing));
    }

    @Test
    void render_aFileBearerAuthFilter_reReadsTheTokenFile_onEveryCall(@TempDir Path dir)
            throws IOException {
        Path tokenFile = dir.resolve("token");
        Files.writeString(tokenFile, "first-token");
        ClientRequestFilter filter = new FileBearerAuthFilter(tokenFile.toString());

        Optional<String> before = AuthorizationHeaderRenderer.render(filter);

        // The rotation a projected serviceaccount token undergoes on disk.
        Files.writeString(tokenFile, "second-token");
        Optional<String> after = AuthorizationHeaderRenderer.render(filter);

        assertEquals(Optional.of("Bearer first-token"), before);
        assertEquals(Optional.of("Bearer second-token"), after,
                "render() must invoke the filter afresh so a rotated token-file is picked up; "
                        + "a memoised value here would silently serve an expired credential");
        assertNotEquals(before, after);
    }

    @Test
    void render_propagatesAFilterFailure_namingTheOffendingFilter() {
        ClientRequestFilter blowsUp = new FileBearerAuthFilter("/no/such/token-file");

        IllegalStateException ex = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> AuthorizationHeaderRenderer.render(blowsUp));

        assertTrue(ex.getMessage().contains("/no/such/token-file"),
                "the failure must name the token-file that could not be read; was: "
                        + ex.getMessage());
    }
}
