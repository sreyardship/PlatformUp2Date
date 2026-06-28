package org.yardship.adapters.out.versionsource.latest.ociregistry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yardship.adapters.out.versionsource.VersionResponseExceptionMapper;
import org.yardship.adapters.out.versionsource.auth.BasicAuthFilter;
import org.yardship.adapters.out.versionsource.auth.BearerAuthFilter;
import org.yardship.core.domain.exceptions.InvalidVersionException;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionValue;
import org.yardship.core.ports.out.LatestVersionSource;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code oci-registry} {@link LatestVersionSource}: reads an image's latest (upstream) version
 * from the OCI Distribution Spec {@code GET /v2/{repo}/tags/list} endpoint.
 *
 * <p>"Latest" is the LARGEST clean semver tag — non-semver tags ({@code latest}, {@code stable},
 * {@code sha-…}) and prerelease/variant tags ({@code 1.22.0-alpine}, {@code 1.22.0-rc1}) are
 * skipped. An empty or all-skipped tag set throws an {@link IllegalStateException}.
 *
 * <p>When the registry returns a {@code 401 WWW-Authenticate: Bearer realm=…} challenge, the source
 * performs the OCI bearer-token dance (ADR-0013): it parses the challenge, mints a short-lived token
 * from the advertised {@code realm} (echoing the {@code service} and {@code scope} verbatim from
 * the challenge; falling back to {@code repository:<repo>:pull} only when {@code scope} is absent),
 * and retries {@code tags/list} with {@code Authorization: Bearer <token>}. A token is minted fresh
 * on every {@link #version()} call — no caching. A registry that responds directly with {@code 200}
 * still works without the dance.
 *
 * <p>Pagination: the full tag set is accumulated by following {@code Link: rel="next"} headers to
 * completion or until {@link TagSelection#maxTags()} is reached. The {@link TagSelection#pageSize()}
 * controls the {@code n} query parameter on every request (default 100). On hitting
 * {@code maxTags} with a {@code next} link still present, the source returns the largest clean
 * semver among the tags SEEN and logs a warning (ADR-0014: truncate-and-warn).
 *
 * <p><b>Exfiltration boundary (ADR-0013):</b> configured {@code basic} credentials (when present)
 * are sent to the registry's advertised {@code realm} host, which is discovered at runtime from the
 * bearer challenge rather than pinned in config.
 *
 * <p>The REST clients are built lazily inside each {@link #version()} invocation so the source can
 * be constructed (by its factory) without a running Quarkus/Arc context, matching
 * {@link org.yardship.adapters.out.versionsource.latest.githubrelease.GithubReleaseLatestSource}.
 */
public class OciRegistryLatestSource implements LatestVersionSource, Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(OciRegistryLatestSource.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern CHALLENGE_PARAM = Pattern.compile("(\\w+)=\"([^\"]*)\"");
    /** Parses the URL inside {@code <…>} from a {@code Link: <url>; rel="next"} header. */
    private static final Pattern LINK_NEXT_URL = Pattern.compile("<([^>]+)>;\\s*rel=\"next\"");
    /** Extracts the {@code last=…} value from a Link URL's query string. */
    private static final Pattern LINK_LAST_PARAM = Pattern.compile("[?&]last=([^&]+)");

    private final String baseUrl;
    private final Optional<String> username;
    private final Optional<String> password;
    private final TagSelection selection;
    private final VersionParser parser;

    /** Internal pagination result for one page: the tag names plus the cursor for the next page. */
    private record TagsPage(List<String> tags, Optional<String> nextLastToken) {}

    /**
     * Internal pagination abstraction unifying the fetch paths (direct-200 first page, each
     * subsequent raw page, and the bearer-dance authenticated pages) behind the single
     * {@link #paginateAndSelectVersion} loop.
     *
     * <p>The {@code lastToken} parameter is {@code null} on the first call (no cursor); on
     * subsequent calls it is the value extracted from the previous page's {@code Link: rel="next"}
     * header. The {@code pageSize} parameter is the configured {@link TagSelection#pageSize()}.
     */
    @FunctionalInterface
    private interface PagedTagsFetcher {
        TagsPage fetch(int pageSize, String lastToken);
    }

    /**
     * Primary (and only) constructor. All tag-selection knobs are collected in {@code selection}.
     *
     * <p><b>Exfiltration boundary (ADR-0013):</b> {@code username} and {@code password} are sent
     * only to the registry's advertised {@code realm}, discovered at runtime from the bearer
     * challenge.
     */
    public OciRegistryLatestSource(String baseUrl, Optional<String> username, Optional<String> password,
                                   TagSelection selection, VersionParser parser) {
        this.baseUrl = baseUrl;
        this.username = username;
        this.password = password;
        this.selection = selection;
        this.parser = parser;
    }

    @Override
    public VersionValue version() {
        return fetchVersionWithDance();
    }

    /**
     * Performs the real HTTP fetch, implementing the OCI bearer-token dance (ADR-0013) when the
     * registry returns a {@code 401} with a {@code WWW-Authenticate: Bearer} challenge. Pagination
     * is handled by the shared {@link #paginateAndSelectVersion} loop via a {@link PagedTagsFetcher}
     * lambda that wraps the appropriate HTTP client.
     *
     * <p>Direct-200 path: the first page is already fetched by the raw probe; subsequent pages are
     * fetched by a new {@link OciRegistryTagsClient} per page. The first page is reused (not
     * re-fetched) to avoid a redundant HTTP call.
     *
     * <p>401 path: the raw 401 response carries no usable tags; a bearer token is minted once, and
     * all pages (including page one) are fetched with the single authenticated client, which is
     * closed after all pages are accumulated.
     */
    private VersionValue fetchVersionWithDance() {
        Response rawFirstResponse = fetchTagsListRawResponse();
        int status = rawFirstResponse.getStatus();

        if (status == 200) {
            // Direct-200 path: no challenge, no dance.
            // Wrap the already-fetched first response and subsequent raw requests in a fetcher.
            PagedTagsFetcher anonymousFetcher = (n, last) -> {
                if (last == null) {
                    // First call: use the response already in hand — no extra HTTP round-trip.
                    return toTagsPage(rawFirstResponse);
                }
                return fetchRawPage(n, last);
            };
            return paginateAndSelectVersion(anonymousFetcher);
        }

        if (status == 401) {
            // Bearer challenge dance: parse → mint → paginate all pages with the minted token.
            String wwwAuthenticate = rawFirstResponse.getHeaderString("WWW-Authenticate");
            BearerChallenge challenge = parseChallenge(wwwAuthenticate);
            String token = mintToken(challenge);
            OciRegistryTagsClient authenticatedClient = buildAuthenticatedTagsClient(token);
            try {
                // Re-use one authenticated client across all pages (one close at the end).
                PagedTagsFetcher authenticatedFetcher =
                        (n, last) -> toTagsPage(authenticatedClient.tagsList(n, last));
                return paginateAndSelectVersion(authenticatedFetcher);
            } finally {
                closeQuietly(authenticatedClient);
            }
        }

        throw new IllegalStateException(
                "Unexpected HTTP " + status + " from " + baseUrl + "/tags/list");
    }

    /**
     * Fetches a single page using a fresh {@link OciRegistryTagsClient}, then closes it.
     * Used for pages 2+ of the anonymous (direct-200) path.
     */
    private TagsPage fetchRawPage(int n, String last) {
        OciRegistryTagsClient rawClient = buildRawTagsClient();
        try {
            return toTagsPage(rawClient.tagsList(n, last));
        } finally {
            closeQuietly(rawClient);
        }
    }

    /**
     * Reads a {@link Response} body as {@link TagsListDTO} and extracts the pagination cursor from
     * the {@code Link} response header. A {@code null} or absent {@code tags} array is treated as an
     * empty page (guard for registries that omit the field on an empty last page).
     */
    private static TagsPage toTagsPage(Response response) {
        TagsListDTO dto = response.readEntity(TagsListDTO.class);
        List<String> tags = (dto.tags != null) ? dto.tags : List.of();
        String linkHeader = response.getHeaderString("Link");
        return new TagsPage(tags, parseNextLastToken(linkHeader));
    }

    /**
     * Parses the {@code last=} cursor from a {@code Link: <url>; rel="next"} header value. Returns
     * {@link Optional#empty()} when the header is absent, has no {@code rel="next"} entry, or the
     * URL contains no {@code last} query parameter.
     */
    private static Optional<String> parseNextLastToken(String linkHeader) {
        if (linkHeader == null) {
            return Optional.empty();
        }
        Matcher urlMatcher = LINK_NEXT_URL.matcher(linkHeader);
        if (!urlMatcher.find()) {
            return Optional.empty();
        }
        Matcher lastMatcher = LINK_LAST_PARAM.matcher(urlMatcher.group(1));
        if (!lastMatcher.find()) {
            return Optional.empty();
        }
        return Optional.of(lastMatcher.group(1));
    }

    /**
     * The single pagination accumulation loop, shared by both the test-seam path (via an injected
     * {@link PagedTagsFetcher}) and the production HTTP paths (which supply a lambda wrapping the
     * appropriate REST client).
     *
     * <p>Terminates when:
     * <ul>
     *   <li>A page has no {@code Link: rel="next"} header — end of the tag set; selects the global
     *       largest clean semver over all accumulated tags.</li>
     *   <li>The accumulated tag count reaches {@link TagSelection#maxTags()} while a {@code next}
     *       link is still present — cap exceeded (ADR-0014 truncate-and-warn); logs a WARNING naming
     *       the source URL and the cap, then selects the largest clean semver from the tags SEEN.</li>
     * </ul>
     *
     * <p>The {@code pageSize} is passed to every {@link PagedTagsFetcher#fetch} call. The
     * {@code lastToken} starts as {@code null} (no cursor) and is threaded from each page's
     * extracted {@code last=} value to the next call.
     */
    private VersionValue paginateAndSelectVersion(PagedTagsFetcher pageFetcher) {
        List<String> allTags = new ArrayList<>();
        String lastToken = null;

        while (true) {
            TagsPage page = pageFetcher.fetch(selection.pageSize(), lastToken);
            List<String> pageTags = (page.tags() != null) ? page.tags() : List.of();
            allTags.addAll(pageTags);

            Optional<String> nextToken = page.nextLastToken();

            if (nextToken.isEmpty()) {
                // Last page — no more data; select over everything seen.
                break;
            }

            if (allTags.size() >= selection.maxTags()) {
                // Cap exceeded with more pages remaining (ADR-0014: truncate-and-warn).
                LOG.warn("OCI registry tag scan capped at max-tags={} for {} — more pages remain. "
                        + "Returning the largest clean semver from the {} tags seen so far "
                        + "(ADR-0014 truncate-and-warn).", selection.maxTags(), baseUrl, allTags.size());
                break;
            }

            lastToken = nextToken.get();
        }

        return selectVersion(allTags);
    }

    /**
     * Makes the initial (unauthenticated) tags/list call and returns the raw {@link Response}.
     *
     * <p>Quarkus's MicroProfile REST Client {@code DefaultMicroprofileRestClientExceptionMapper}
     * fires on non-2xx responses (including {@code 401}) even for {@link Response}-typed methods.
     * We catch the resulting {@link jakarta.ws.rs.WebApplicationException} and extract the actual
     * HTTP response from it — the response object is preserved on the exception and carries the
     * {@code WWW-Authenticate} header needed for the bearer-token dance.
     */
    private Response fetchTagsListRawResponse() {
        OciRegistryTagsClient rawClient = buildRawTagsClient();
        try {
            return rawClient.tagsList(selection.pageSize(), null);
        } catch (jakarta.ws.rs.WebApplicationException wae) {
            Response response = wae.getResponse();
            if (response != null) {
                return response;
            }
            if (wae.getCause() instanceof jakarta.ws.rs.WebApplicationException causeWae
                    && causeWae.getResponse() != null) {
                return causeWae.getResponse();
            }
            throw new IllegalStateException(
                    "Unexpected error from " + baseUrl + "/tags/list (no response available)", wae);
        } finally {
            closeQuietly(rawClient);
        }
    }

    /**
     * Builds the tags client WITHOUT the {@link VersionResponseExceptionMapper} and without an auth
     * filter — for the initial probe (where a {@code 401} is expected and inspected for the bearer
     * challenge) and for anonymous pages 2+. Same {@link OciRegistryTagsClient} interface as
     * {@link #buildAuthenticatedTagsClient}; only the registered providers differ.
     */
    private OciRegistryTagsClient buildRawTagsClient() {
        return QuarkusRestClientBuilder.newBuilder()
                .baseUri(URI.create(baseUrl))
                .build(OciRegistryTagsClient.class);
    }

    /**
     * Builds the tags client WITH the {@link BearerAuthFilter} (minted token) and the
     * {@link VersionResponseExceptionMapper} (so a non-2xx on the authenticated retry surfaces as a
     * clear failure). Same {@link OciRegistryTagsClient} interface as {@link #buildRawTagsClient};
     * only the registered providers differ.
     */
    private OciRegistryTagsClient buildAuthenticatedTagsClient(String bearerToken) {
        return QuarkusRestClientBuilder.newBuilder()
                .baseUri(URI.create(baseUrl))
                .register(VersionResponseExceptionMapper.class)
                .register(new BearerAuthFilter(bearerToken))
                .build(OciRegistryTagsClient.class);
    }

    /**
     * Mints a bearer token from the realm advertised in {@code challenge}. Sends
     * {@code Authorization: Basic base64(user:pass)} to the realm when both username and password
     * are present; otherwise mints anonymously.
     */
    private String mintToken(BearerChallenge challenge) {
        QuarkusRestClientBuilder builder = QuarkusRestClientBuilder.newBuilder()
                .baseUri(URI.create(challenge.realm()));
        username.filter(u -> !u.isBlank())
                .flatMap(u -> password.filter(p -> !p.isBlank()).map(p -> new BasicAuthFilter(u, p)))
                .ifPresent(builder::register);
        OciTokenClient tokenClient = builder.build(OciTokenClient.class);
        try {
            Response tokenResponse = tokenClient.mint(challenge.service(), challenge.scope());
            return extractToken(tokenResponse);
        } finally {
            closeQuietly(tokenClient);
        }
    }

    /**
     * Parses {@code WWW-Authenticate: Bearer realm="…",service="…"[,scope="…"]} and returns a
     * {@link BearerChallenge} with values echoed verbatim from the challenge. When {@code scope} is
     * absent, falls back to {@code repository:<repo>:pull} (ADR-0013).
     */
    private BearerChallenge parseChallenge(String wwwAuthenticate) {
        if (wwwAuthenticate == null || !wwwAuthenticate.startsWith("Bearer ")) {
            throw new IllegalStateException(
                    "Expected WWW-Authenticate: Bearer challenge, got: " + wwwAuthenticate);
        }
        Matcher matcher = CHALLENGE_PARAM.matcher(wwwAuthenticate);
        Map<String, String> params = new LinkedHashMap<>();
        while (matcher.find()) {
            params.put(matcher.group(1), matcher.group(2));
        }
        String realm = params.get("realm");
        if (realm == null || realm.isBlank()) {
            throw new IllegalStateException(
                    "Bearer challenge missing 'realm' in: " + wwwAuthenticate);
        }
        String service = params.getOrDefault("service", "");
        String scope = params.containsKey("scope") ? params.get("scope") : fallbackScope();
        return new BearerChallenge(realm, service, scope);
    }

    /**
     * Constructs a fallback OCI pull scope for registries that omit {@code scope} from the
     * challenge. Extracts the {@code <repo>} path component from {@code baseUrl}
     * ({@code http://host/v2/<repo>}) and returns {@code repository:<repo>:pull} (ADR-0013).
     */
    private String fallbackScope() {
        int v2Idx = baseUrl.indexOf("/v2/");
        String repo = v2Idx >= 0 ? baseUrl.substring(v2Idx + 4) : baseUrl;
        return "repository:" + repo + ":pull";
    }

    /**
     * Extracts the bearer token value from a token-mint response JSON body. Prefers the
     * {@code token} field; falls back to {@code access_token} (ADR-0013).
     */
    private static String extractToken(Response tokenResponse) {
        String json = tokenResponse.readEntity(String.class);
        try {
            JsonNode node = OBJECT_MAPPER.readTree(json);
            if (node.has("token") && !node.get("token").isNull()) {
                return node.get("token").asText();
            }
            if (node.has("access_token") && !node.get("access_token").isNull()) {
                return node.get("access_token").asText();
            }
            throw new IllegalStateException(
                    "Token response contained neither 'token' nor 'access_token': " + json);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to parse token response: " + json, ex);
        }
    }

    /**
     * Selects the largest eligible version from an accumulated tag list. When
     * {@link TagSelection#prereleaseFilter()} is present, delegates to
     * {@link #selectVersionWithPrereleaseFilter(List, String)} which opts exactly one prerelease
     * flavour back in (ADR-0014). When absent, selects the largest CLEAN semver (no prerelease
     * segment) — the original slice-01 behaviour.
     */
    private VersionValue selectVersion(List<String> tags) {
        if (selection.prereleaseFilter().isPresent()) {
            return selectVersionWithPrereleaseFilter(tags, selection.prereleaseFilter().get());
        }
        return selectLargestCleanVersion(tags);
    }

    /**
     * Selects the largest tag whose semver prerelease segment (dot-joined) EXACTLY equals
     * {@code filter}. The full original tag string is reported (e.g. {@code 1.22.0-alpine}), unless
     * {@link TagSelection#stripPrerelease()} is {@code true} in which case the prerelease segment is
     * cleared before reporting (e.g. {@code 1.22.0}).
     * When no tag matches the filter, throws {@link IllegalStateException}.
     *
     * <p>Logic:
     * <ol>
     *   <li>Parses each tag as semver (skipping non-semver silently).</li>
     *   <li>Retains only tags whose {@code preReleaseSegment()} equals {@code filter} (exact match,
     *       not prefix: {@code "alpine"} matches {@code 1.22.0-alpine} but NOT {@code 1.22.0-alpine3.16}).</li>
     *   <li>Returns the largest among them (by semver precedence) — reporting the FULL tag value by
     *       default, or the stripped version when {@link TagSelection#stripPrerelease()} is true.</li>
     *   <li>Throws when zero tags survive the filter — per-app scrape failure.</li>
     * </ol>
     */
    private VersionValue selectVersionWithPrereleaseFilter(List<String> tags, String filter) {
        VersionValue selected = tags.stream()
                .map(this::tryParseVersion)
                .flatMap(Optional::stream)
                .filter(v -> v.preReleaseSegment().filter(filter::equals).isPresent())
                .reduce((current, candidate) -> current.isOlderThan(candidate) ? candidate : current)
                .orElseThrow(() -> new IllegalStateException(
                        "No tag with prerelease segment '" + filter
                        + "' found in OCI registry tag list for: " + baseUrl));
        return selection.stripPrerelease() ? selected.withoutPreRelease() : selected;
    }

    private Optional<VersionValue> tryParseVersion(String tag) {
        try {
            return Optional.of(parser.parse(tag));
        } catch (InvalidVersionException ex) {
            return Optional.empty();
        }
    }

    /**
     * Selects the largest clean semver from an accumulated tag list.
     * "Clean" means no prerelease segment (e.g. {@code 1.22.0-alpine} is skipped).
     */
    private VersionValue selectLargestCleanVersion(List<String> tags) {
        return tags.stream()
                .map(this::tryParseCleanVersion)
                .flatMap(Optional::stream)
                .reduce((current, candidate) -> current.isOlderThan(candidate) ? candidate : current)
                .orElseThrow(() -> new IllegalStateException(
                        "No clean semver tag found in OCI registry tag list for: " + baseUrl));
    }

    private Optional<VersionValue> tryParseCleanVersion(String tag) {
        return tryParseVersion(tag)
                .filter(v -> v.preReleaseSegment().isEmpty());
    }

    /**
     * Best-effort close for a transiently-built REST client. Silently ignores {@link IOException}
     * so a failed close never masks a real result or exception from the caller's {@code finally}
     * block.
     */
    private static void closeQuietly(Object client) {
        if (client instanceof Closeable c) {
            try {
                c.close();
            } catch (IOException ignored) {
                // best-effort; do not mask the real result or exception
            }
        }
    }

    @Override
    public void close() throws IOException {
        // No persistent resources to close in the new design (fetcher is externally owned).
    }

    /** Parsed fields from a {@code WWW-Authenticate: Bearer} challenge (ADR-0013). */
    private record BearerChallenge(String realm, String service, String scope) {}
}
