package org.yardship.adapters.out.versionsource.latest.ociregistry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yardship.adapters.out.versionsource.auth.BasicAuthFilter;
import org.yardship.adapters.out.versionsource.auth.BearerAuthFilter;
import org.yardship.adapters.out.versionsource.http.RedirectFollowingHttpGet;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionValue;
import org.yardship.core.ports.out.LatestVersionSource;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
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
    /** Bound on the response body length included in a scrape-failure diagnostic message. */
    private static final int MAX_BODY_LENGTH = 512;

    private final String baseUrl;
    private final Optional<String> username;
    private final Optional<String> password;
    private final TagSelection selection;
    private final OciTagSelector tagSelector;
    private final RedirectFollowingHttpGet redirectFollowingHttpGet = new RedirectFollowingHttpGet();

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
        this.tagSelector = new OciTagSelector(selection, parser, baseUrl);
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
     * fetched via {@link #fetchAnonymousPage}. The first page is reused (not re-fetched) to avoid a
     * redundant HTTP call.
     *
     * <p>401 path: the raw 401 response carries no usable tags; a bearer token is minted once
     * ({@link #mintToken}), and all pages (including page one) are fetched via
     * {@link #fetchAuthenticatedPage} carrying that token.
     */
    private VersionValue fetchVersionWithDance() {
        AnonymousPageFetch rawFirst = fetchAnonymousPage(buildTagsListUri(selection.pageSize(), null));
        Response rawFirstResponse = rawFirst.response();
        int status = rawFirstResponse.getStatus();

        if (status == 200) {
            // Direct-200 path: no challenge, no dance.
            // Wrap the already-fetched first response and subsequent raw requests in a fetcher.
            // The NEXT page is fetched at the absolute URI resolved from the Link header of the
            // page just fetched — never reconstructed from baseUrl — so a first page served via a
            // redirect to a canonical endpoint (ADR-0029) still composes with Link pagination: the
            // canonical endpoint's OWN Link header, not the origin's, drives page 2+.
            AtomicReference<Optional<URI>> nextUri = new AtomicReference<>(rawFirst.nextUri());
            PagedTagsFetcher anonymousFetcher = (n, last) -> {
                if (last == null) {
                    // First call: use the response already in hand — no extra HTTP round-trip.
                    return toTagsPage(rawFirstResponse);
                }
                AnonymousPageFetch fetch = fetchAnonymousPage(
                        nextUri.get().orElseGet(() -> buildTagsListUri(n, last)));
                requireSuccessfulTagsPageResponse(fetch.response());
                nextUri.set(fetch.nextUri());
                return toTagsPage(fetch.response());
            };
            return paginateAndSelectVersion(anonymousFetcher);
        }

        if (status == 401) {
            // Bearer challenge dance: parse → mint → paginate all pages with the minted token.
            String wwwAuthenticate = rawFirstResponse.getHeaderString("WWW-Authenticate");
            BearerChallenge challenge = parseChallenge(wwwAuthenticate);
            String token = mintToken(challenge);
            // The NEXT authenticated page is fetched at the absolute URI resolved from the Link
            // header of the page just fetched — never reconstructed from baseUrl — mirroring the
            // anonymous path (see AnonymousPageFetch) so a redirected canonical authenticated
            // endpoint (ADR-0029) still paginates correctly.
            AtomicReference<Optional<URI>> nextUri = new AtomicReference<>(Optional.empty());
            PagedTagsFetcher authenticatedFetcher = (n, last) -> {
                URI uri = nextUri.get().orElseGet(() -> buildTagsListUri(n, last));
                AuthenticatedPageFetch fetch = fetchAuthenticatedPage(uri, token);
                nextUri.set(fetch.nextUri());
                return toTagsPage(fetch.response());
            };
            return paginateAndSelectVersion(authenticatedFetcher);
        }

        throw new IllegalStateException(
                "Unexpected HTTP " + status + " from " + baseUrl + "/tags/list");
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

        return tagSelector.select(allTags);
    }

    /**
     * One anonymous (unauthenticated) {@code tags/list} fetch, pairing the adapted
     * {@link Response} with the absolute URI of the NEXT page (if any), resolved from that same
     * response's {@code Link} header. Kept separate from {@link TagsPage#nextLastToken()} (the
     * bare {@code last=} value used by the authenticated/token legs, untouched by this slice):
     * once a page has been reached via a redirect to a canonical endpoint (ADR-0029), the next
     * page must be requested at the canonical endpoint's own next-link URI, not reconstructed
     * against {@link #baseUrl}, which may no longer be where the registry is actually serving
     * pages from.
     */
    private record AnonymousPageFetch(Response response, Optional<URI> nextUri) {}

    /**
     * Fetches one anonymous {@code tags/list} page at {@code uri} via {@link RedirectFollowingHttpGet}
     * (ADR-0029) — used for both the initial probe and every subsequent anonymous page — and adapts
     * the result into the {@link Response}-shaped flow ({@link #toTagsPage} / {@link #parseChallenge})
     * that the rest of this class already understands. A {@code 401} is never a redirect status, so
     * it comes back UNFOLLOWED, with its {@code WWW-Authenticate} header intact for the bearer-token
     * dance. Nothing about a resolved redirect target is cached: {@code uri} is built fresh by the
     * caller on every call.
     */
    private AnonymousPageFetch fetchAnonymousPage(URI uri) {
        HttpResponse<String> httpResponse =
                retryOnConnectionFailure(() -> redirectFollowingHttpGet.get(uri, Map.of()));
        Response response = toJaxRsResponse(httpResponse);
        Optional<URI> nextUri = httpResponse.headers().firstValue("Link")
                .flatMap(OciRegistryLatestSource::parseNextLinkUrl)
                .map(httpResponse.uri()::resolve);
        return new AnonymousPageFetch(response, nextUri);
    }

    /**
     * Builds the {@code tags/list} request URI for the anonymous path, carrying the {@code n}
     * page-size query parameter and — when non-null — the {@code last} pagination cursor.
     */
    private URI buildTagsListUri(int pageSize, String last) {
        StringBuilder query = new StringBuilder(baseUrl).append("/tags/list?n=").append(pageSize);
        if (last != null) {
            query.append("&last=").append(java.net.URLEncoder.encode(last, StandardCharsets.UTF_8));
        }
        return URI.create(query.toString());
    }

    /**
     * Adapts a {@link RedirectFollowingHttpGet} result into a {@link jakarta.ws.rs.core.Response}
     * so the existing {@link #toTagsPage} (200 path) and {@link #parseChallenge} (401 path) logic
     * keeps working unchanged regardless of which transport fetched the page.
     *
     * <p>On {@code 200}, the body is parsed into a {@link TagsListDTO} up front and set as the
     * entity directly — {@link Response#readEntity(Class)} on a manually-built response returns an
     * already-matching entity instance as-is, without needing a wired {@code MessageBodyReader}.
     * On any other status the raw body string is kept as the entity (unused by the 401 dance, which
     * only inspects the header). The {@code Link} and {@code WWW-Authenticate} headers are copied
     * verbatim so pagination and the bearer challenge keep working.
     */
    private static Response toJaxRsResponse(HttpResponse<String> httpResponse) {
        int status = httpResponse.statusCode();
        Response.ResponseBuilder builder = Response.status(status)
                .entity(status == 200 ? parseTagsListDto(httpResponse.body()) : httpResponse.body());
        httpResponse.headers().firstValue("Link").ifPresent(value -> builder.header("Link", value));
        httpResponse.headers().firstValue("WWW-Authenticate")
                .ifPresent(value -> builder.header("WWW-Authenticate", value));
        return builder.build();
    }

    /**
     * Extracts the raw next-page href from a {@code Link: <url>; rel="next"} header value — the
     * counterpart to {@link #parseNextLastToken} (which extracts only the {@code last=} value for
     * the authenticated/token legs). Returns {@link Optional#empty()} when the header has no
     * {@code rel="next"} entry.
     */
    private static Optional<String> parseNextLinkUrl(String linkHeader) {
        Matcher urlMatcher = LINK_NEXT_URL.matcher(linkHeader);
        return urlMatcher.find() ? Optional.of(urlMatcher.group(1)) : Optional.empty();
    }

    /** Parses a {@code tags/list} 200 response body into a {@link TagsListDTO}. */
    private static TagsListDTO parseTagsListDto(String json) {
        try {
            return OBJECT_MAPPER.readValue(json, TagsListDTO.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse OCI tags/list response body: " + json, e);
        }
    }

    /**
     * One authenticated {@code tags/list} fetch, pairing the adapted {@link Response} with the
     * absolute URI of the NEXT page (if any) — the authenticated-leg counterpart to
     * {@link #fetchAnonymousPage}. Fetched via {@link RedirectFollowingHttpGet} (ADR-0029) with only
     * the minted Bearer token in the header map: on a same-origin redirect it is retained, on a
     * cross-origin redirect it is stripped, and an HTTPS→HTTP downgrade is refused before the
     * plain-HTTP target is ever contacted.
     */
    private record AuthenticatedPageFetch(Response response, Optional<URI> nextUri) {}

    private AuthenticatedPageFetch fetchAuthenticatedPage(URI uri, String bearerToken) {
        Map<String, String> headers = Map.of("Authorization", BearerAuthFilter.bearerHeaderValue(bearerToken));
        HttpResponse<String> httpResponse =
                retryOnConnectionFailure(() -> redirectFollowingHttpGet.get(uri, headers));
        Response response = toJaxRsResponse(httpResponse);
        // Every call here fetches an authenticated (post-mint) tags page — never the raw probe
        // whose 401 drives the bearer dance (that response is inspected separately in
        // fetchVersionWithDance and never passed through this method) — so any non-2xx here is
        // an unexpected scrape failure, not a legitimate challenge.
        requireSuccessfulTagsPageResponse(response);
        Optional<URI> nextUri = httpResponse.headers().firstValue("Link")
                .flatMap(OciRegistryLatestSource::parseNextLinkUrl)
                .map(httpResponse.uri()::resolve);
        return new AuthenticatedPageFetch(response, nextUri);
    }

    /**
     * Diagnostic guard for a page fetch that is EXPECTED to be 2xx (an authenticated tags page, or
     * an anonymous page 2+): on a non-2xx, fails closed with an {@link IllegalStateException}
     * carrying the HTTP status and a truncated body — restoring the informativeness the old
     * {@code VersionResponseExceptionMapper}-based design gave for free — BEFORE the response is
     * handed to {@link #toTagsPage}, whose generic {@code readEntity} {@code ProcessingException}
     * would otherwise swallow both the status and the body.
     *
     * <p>Must never be called on the raw first-page probe: its {@code 401} is a legitimate bearer
     * challenge, handled by {@link #parseChallenge} in {@link #fetchVersionWithDance}, not a
     * failure.
     */
    private static void requireSuccessfulTagsPageResponse(Response response) {
        int status = response.getStatus();
        if (status < 200 || status >= 300) {
            String body = response.hasEntity() ? String.valueOf(response.getEntity()) : "";
            throw new IllegalStateException("HTTP " + status + " response: " + truncate(body));
        }
    }

    /** Truncates a response body to a bounded length for safe inclusion in an error message. */
    private static String truncate(String body) {
        if (body == null) {
            return "null";
        }
        return body.length() <= MAX_BODY_LENGTH ? body : body.substring(0, MAX_BODY_LENGTH) + "…[truncated]";
    }

    /**
     * Mints a bearer token from the realm advertised in {@code challenge}, via
     * {@link RedirectFollowingHttpGet} (ADR-0029) so a redirected realm is followed to its canonical
     * token endpoint. Sends {@code Authorization: Basic base64(user:pass)} ({@link
     * BasicAuthFilter#basicHeaderValue}) to the realm when both username and password are present;
     * otherwise mints anonymously (empty header map). The {@code service} and {@code scope} values
     * are echoed verbatim from the challenge as URL-encoded query parameters on the realm URI — the
     * transport itself retains the credential on same-origin hops only and strips it cross-origin.
     */
    private String mintToken(BearerChallenge challenge) {
        URI realmUri = buildTokenRealmUri(challenge);
        Map<String, String> headers = username.filter(u -> !u.isBlank())
                .flatMap(u -> password.filter(p -> !p.isBlank())
                        .map(p -> BasicAuthFilter.basicHeaderValue(u, p)))
                .map(basic -> Map.of("Authorization", basic))
                .orElseGet(Map::of);
        HttpResponse<String> tokenResponse =
                retryOnConnectionFailure(() -> redirectFollowingHttpGet.get(realmUri, headers));
        return extractToken(tokenResponse.body());
    }

    /**
     * Builds the token-mint request URI: the challenge's {@code realm}, verbatim, with the
     * challenge's {@code service} and {@code scope} appended as URL-encoded query parameters.
     */
    private static URI buildTokenRealmUri(BearerChallenge challenge) {
        String query = "service=" + URLEncoder.encode(challenge.service(), StandardCharsets.UTF_8)
                + "&scope=" + URLEncoder.encode(challenge.scope(), StandardCharsets.UTF_8);
        String separator = challenge.realm().contains("?") ? "&" : "?";
        return URI.create(challenge.realm() + separator + query);
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
    private static String extractToken(String json) {
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
     * Invokes an idempotent HTTP call, retrying it once when it fails at the connection level —
     * most commonly the HTTP/1.1 keep-alive close race, where the server closes a connection at
     * the same moment the client sends a request on it. RFC 9112 §9.3.1 acknowledges this race
     * and directs clients to automatically retry idempotent requests on it; every call this
     * source makes is a GET, so the retry is always safe.
     *
     * <p>Only transport failures caused by an {@link IOException} are retried — every leg (raw
     * probe, token mint, authenticated tags) is wrapped as a plain {@link RuntimeException} by
     * {@link RedirectFollowingHttpGet} (ADR-0029). HTTP error responses (any status, including the
     * expected 401 challenge) and non-I/O failures propagate untouched on the first attempt.
     */
    private static <T> T retryOnConnectionFailure(Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException firstAttempt) {
            if (!causedByIoFailure(firstAttempt)) {
                throw firstAttempt;
            }
            LOG.debug("Connection-level failure on idempotent OCI registry call; retrying once "
                    + "(RFC 9112 keep-alive close race)", firstAttempt);
            return call.get();
        }
    }

    /** Walks the cause chain looking for an {@link IOException} (connection-level failure). */
    private static boolean causedByIoFailure(Throwable failure) {
        for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() throws IOException {
        // No persistent resources to close in the new design (fetcher is externally owned).
    }

    /** Parsed fields from a {@code WWW-Authenticate: Bearer} challenge (ADR-0013). */
    private record BearerChallenge(String realm, String service, String scope) {}
}
