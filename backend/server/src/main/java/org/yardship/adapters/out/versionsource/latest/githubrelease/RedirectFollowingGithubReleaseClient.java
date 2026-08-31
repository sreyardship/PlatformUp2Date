package org.yardship.adapters.out.versionsource.latest.githubrelease;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.yardship.adapters.out.versionsource.VersionFetchException;
import org.yardship.adapters.out.versionsource.VersionResponseExceptionMapper;
import org.yardship.adapters.out.versionsource.auth.BearerAuthFilter;
import org.yardship.adapters.out.versionsource.http.RedirectFollowingHttpGet;

import java.net.URI;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fetches {@code /releases} over {@link RedirectFollowingHttpGet} instead of a declarative
 * REST-client proxy, so a 301/302/303/307/308 response (e.g. GitHub's "repository moved" redirect
 * from issue #39) is followed per ADR-0029 rather than immediately surfacing as a failure.
 *
 * <p>Reuses {@link BearerAuthFilter#bearerHeaderValue(String)} for the exact credential format the
 * {@code latest} leg has always sent, and {@link VersionResponseExceptionMapper} for the exact
 * final-non-2xx-to-{@link VersionFetchException} mapping the rest of the codebase relies on — this
 * class only owns the transport (redirect-following GET + JSON body decoding), not the auth-header
 * format or the failure-mapping policy.
 *
 * <p>Deliberately does NOT {@code implement GithubReleaseClient}: that interface carries
 * {@code @Path}, and a real (build-time-indexed) class implementing it gets swept up by Quarkus's
 * JAX-RS/CDI scanning as a resource/bean — its constructor parameters then fail CDI injection.
 * {@link GithubReleaseLatestSource} instead wires an instance in via a method reference
 * ({@code transport::releases}), which is a synthetic lambda class invisible to that scanning.
 */
class RedirectFollowingGithubReleaseClient {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final VersionResponseExceptionMapper EXCEPTION_MAPPER = new VersionResponseExceptionMapper();

    private final String baseUrl;
    private final Optional<String> token;
    private final RedirectFollowingHttpGet httpGet;

    RedirectFollowingGithubReleaseClient(String baseUrl, Optional<String> token) {
        this.baseUrl = baseUrl;
        this.token = token;
        this.httpGet = new RedirectFollowingHttpGet();
    }

    List<GithubReleaseResponseDTO> releases(int perPage) {
        URI uri = URI.create(baseUrl + "/releases?per_page=" + perPage);
        HttpResponse<String> response = httpGet.get(uri, requestHeaders());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw EXCEPTION_MAPPER.toThrowable(toJaxRsResponse(response));
        }
        return parseReleases(response.body());
    }

    private Map<String, String> requestHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        token.filter(value -> !value.isBlank())
                .ifPresent(value -> headers.put(HttpHeaders.AUTHORIZATION, BearerAuthFilter.bearerHeaderValue(value)));
        return headers;
    }

    private static List<GithubReleaseResponseDTO> parseReleases(String body) {
        try {
            return OBJECT_MAPPER.readValue(body, new TypeReference<List<GithubReleaseResponseDTO>>() { });
        }
        catch (Exception e) {
            throw new VersionFetchException(
                    "Failed to parse GitHub releases response: " + e.getMessage(), 200, body);
        }
    }

    private static Response toJaxRsResponse(HttpResponse<String> response) {
        return Response.status(response.statusCode()).entity(response.body()).build();
    }
}
