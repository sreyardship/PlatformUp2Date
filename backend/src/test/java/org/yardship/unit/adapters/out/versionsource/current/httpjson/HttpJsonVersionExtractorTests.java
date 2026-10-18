package org.yardship.unit.adapters.out.versionsource.current.httpjson;

import org.junit.jupiter.api.Test;
import org.yardship.adapters.out.versionsource.VersionFetchException;
import org.yardship.adapters.out.versionsource.current.httpjson.HttpJsonVersionExtractor;
import org.yardship.adapters.out.versionsource.http.HttpGetResponse;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionScheme;
import org.yardship.core.domain.primitives.VersionValue;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link HttpJsonVersionExtractor}, the {@code http-json} rule applied to the final
 * response the shared transport returns: a 2xx gate, a JSON body decode, a JSON Pointer (RFC 6901)
 * into it, and optional prerelease stripping. Fed fake {@link HttpGetResponse}s — NO Arc, NO HTTP.
 */
class HttpJsonVersionExtractorTests {

    private static final VersionParser SEMVER_PARSER = new VersionParser(VersionScheme.SEMVER);

    @Test
    void extract_resolvesTopLevelPointer_intoVersion() {
        VersionValue result = extractor("/version", false).extract(ok("{\"version\":\"1.0.0\"}"));

        assertEquals("1.0.0", result.value());
    }

    @Test
    void extract_resolvesNonStandardTopLevelPointer() {
        VersionValue result = extractor("/harbor_version", false)
                .extract(ok("{\"harbor_version\":\"v2.11.1-6b7ecba1\", \"other\":\"x\"}"));

        assertTrue(result.value().contains("2.11.1"));
    }

    @Test
    void extract_resolvesNestedPointer() {
        VersionValue result = extractor("/data/version", false).extract(ok("{\"data\":{\"version\":\"3.4.5\"}}"));

        assertEquals("3.4.5", result.value());
    }

    @Test
    void extract_withStripPrereleaseTrue_clearsThePreReleaseSegment() {
        VersionValue result = extractor("/harbor_version", true)
                .extract(ok("{\"harbor_version\":\"v2.11.1-6b7ecba1\"}"));

        assertEquals("2.11.1", result.value());
    }

    @Test
    void extract_withStripPrereleaseFalse_preservesThePreReleaseSegment() {
        VersionValue result = extractor("/harbor_version", false)
                .extract(ok("{\"harbor_version\":\"v2.11.1-6b7ecba1\"}"));

        assertTrue(result.value().contains("-6b7ecba1"),
                "with strip-prerelease false, the prerelease segment must be preserved; was: " + result.value());
    }

    @Test
    void extract_throws_withClearTruncatedBodyMessage_whenPointerDoesNotResolve() {
        HttpJsonVersionExtractor extractor = extractor("/missing", false);
        HttpGetResponse response = ok("{\"auth_mode\":\"oidc_auth\"}");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> extractor.extract(response));

        assertTrue(ex.getMessage().contains("/missing"),
                "the failure message must name the unresolved pointer; was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("auth_mode"),
                "the failure message must include the (truncated) upstream body; was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("'http-json'"),
                "the failure message must name the 'http-json' kind label; was: " + ex.getMessage());
    }

    @Test
    void extract_throws_withClearMessage_whenPointerResolvesToANonTextualValue() {
        HttpJsonVersionExtractor extractor = extractor("/version", false);
        HttpGetResponse response = ok("{\"version\":12345}");

        RuntimeException ex = assertThrows(RuntimeException.class, () -> extractor.extract(response));

        assertTrue(ex.getMessage().contains("/version"),
                "the failure message must name the pointer; was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("12345"),
                "the failure message must include the (truncated) upstream body; was: " + ex.getMessage());
    }

    // --- the 2xx gate lives here, not in the transport ----------------------------------------

    @Test
    void extract_throwsAVersionFetchException_onANon2xxResponse_namingTheStatusAndTheBody() {
        HttpJsonVersionExtractor extractor = extractor("/version", false);
        HttpGetResponse forbidden = new HttpGetResponse(403, Map.of(), "{\"message\":\"forbidden\"}");

        VersionFetchException ex = assertThrows(VersionFetchException.class, () -> extractor.extract(forbidden));

        assertEquals(403, ex.status());
        assertTrue(ex.getMessage().contains("403"), "must name the status; was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("forbidden"), "must carry the body; was: " + ex.getMessage());
    }

    @Test
    void extract_onANon2xxResponse_truncatesALongBodyInTheMessage() {
        HttpJsonVersionExtractor extractor = extractor("/version", false);
        String longBody = "x".repeat(2000);
        HttpGetResponse error = new HttpGetResponse(500, Map.of(), longBody);

        VersionFetchException ex = assertThrows(VersionFetchException.class, () -> extractor.extract(error));

        assertFalse(ex.getMessage().contains(longBody),
                "the message must not carry the whole body; was " + ex.getMessage().length() + " chars");
        assertTrue(ex.getMessage().contains("truncated"),
                "the message must say the body was truncated; was: " + ex.getMessage());
        assertEquals(longBody, ex.body(), "the exception itself still carries the full body");
    }

    @Test
    void extract_throwsAVersionFetchException_whenA2xxBodyIsNotJson() {
        HttpJsonVersionExtractor extractor = extractor("/version", false);
        HttpGetResponse html = new HttpGetResponse(200, Map.of("Content-Type", List.of("text/html")),
                "<html>login</html>");

        VersionFetchException ex = assertThrows(VersionFetchException.class, () -> extractor.extract(html));

        assertTrue(ex.getMessage().contains("'http-json'"),
                "the failure message must name the 'http-json' kind label; was: " + ex.getMessage());
        assertTrue(ex.getMessage().toLowerCase().contains("json"),
                "the failure message must say the body was not JSON; was: " + ex.getMessage());
    }

    private static HttpJsonVersionExtractor extractor(String versionKey, boolean stripPrerelease) {
        return new HttpJsonVersionExtractor(versionKey, stripPrerelease, SEMVER_PARSER);
    }

    private static HttpGetResponse ok(String body) {
        return new HttpGetResponse(200, Map.of("Content-Type", List.of("application/json")), body);
    }
}
