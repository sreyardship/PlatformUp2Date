package org.yardship.unit.adapters.out.versionsource.current.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.current.http.HttpCurrentVersionClient;
import org.yardship.adapters.out.versionsource.current.http.HttpCurrentSource;
import org.yardship.core.domain.primitives.Version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link HttpCurrentSource} as a pure POJO — NO Arc, NO WireMock. It is constructed
 * directly with a ready (fake) {@link HttpCurrentVersionClient}, a {@code version-key} JSON Pointer, and
 * a {@code strip-prerelease} flag, and does only {@code JsonNode.at(pointer)} extraction plus optional
 * prerelease stripping. The REST-client-building concern lives entirely in
 * {@code HttpCurrentVersionClientFactory} now and is exercised separately at the integration level.
 *
 * <p>This rehomes the construction/extraction coverage that previously lived only in
 * {@code HttpCurrentSourceIT} (which directly built {@code QuarkusRestClientBuilder} clients) down to
 * a true, fast unit test — per plan.md's "Migrates the current IT-only construction concern down to a
 * true unit test."
 */
class HttpCurrentSourceTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void version_resolvesTopLevelPointer_intoVersion() throws Exception {
        JsonNode body = MAPPER.readTree("{\"version\":\"1.0.0\"}");
        HttpCurrentSource source = new HttpCurrentSource(fakeClient(body), "/version", false);

        Version result = source.version();

        assertEquals("1.0.0", result.value());
    }

    @Test
    void version_resolvesNonStandardTopLevelPointer() throws Exception {
        JsonNode body = MAPPER.readTree("{\"harbor_version\":\"v2.11.1-6b7ecba1\", \"other\":\"x\"}");
        HttpCurrentSource source = new HttpCurrentSource(fakeClient(body), "/harbor_version", false);

        Version result = source.version();

        assertTrue(result.value().contains("2.11.1"));
    }

    @Test
    void version_resolvesNestedPointer() throws Exception {
        JsonNode body = MAPPER.readTree("{\"data\":{\"version\":\"3.4.5\"}}");
        HttpCurrentSource source = new HttpCurrentSource(fakeClient(body), "/data/version", false);

        Version result = source.version();

        assertEquals("3.4.5", result.value());
    }

    @Test
    void version_withStripPrereleaseTrue_clearsThePreReleaseSegment() throws Exception {
        JsonNode body = MAPPER.readTree("{\"harbor_version\":\"v2.11.1-6b7ecba1\"}");
        HttpCurrentSource source = new HttpCurrentSource(fakeClient(body), "/harbor_version", true);

        Version result = source.version();

        assertEquals("2.11.1", result.value());
    }

    @Test
    void version_withStripPrereleaseFalse_preservesThePreReleaseSegment() throws Exception {
        JsonNode body = MAPPER.readTree("{\"harbor_version\":\"v2.11.1-6b7ecba1\"}");
        HttpCurrentSource source = new HttpCurrentSource(fakeClient(body), "/harbor_version", false);

        Version result = source.version();

        assertTrue(result.value().contains("-6b7ecba1"),
                "with strip-prerelease false, the prerelease segment must be preserved; was: "
                        + result.value());
    }

    @Test
    void version_throws_withClearTruncatedBodyMessage_whenPointerDoesNotResolve() throws Exception {
        JsonNode body = MAPPER.readTree("{\"auth_mode\":\"oidc_auth\"}");
        HttpCurrentSource source = new HttpCurrentSource(fakeClient(body), "/missing", false);

        RuntimeException ex = assertThrows(RuntimeException.class, source::version);

        assertTrue(ex.getMessage().contains("/missing"),
                "the failure message must name the unresolved pointer; was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("auth_mode"),
                "the failure message must include the (truncated) upstream body; was: " + ex.getMessage());
    }

    @Test
    void version_throws_withClearMessage_whenPointerResolvesToANonTextualValue() throws Exception {
        JsonNode body = MAPPER.readTree("{\"version\":12345}");
        HttpCurrentSource source = new HttpCurrentSource(fakeClient(body), "/version", false);

        RuntimeException ex = assertThrows(RuntimeException.class, source::version);

        assertTrue(ex.getMessage().contains("/version"),
                "the failure message must name the pointer; was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("12345"),
                "the failure message must include the (truncated) upstream body; was: " + ex.getMessage());
    }

    private static HttpCurrentVersionClient fakeClient(JsonNode body) {
        return () -> body;
    }
}
