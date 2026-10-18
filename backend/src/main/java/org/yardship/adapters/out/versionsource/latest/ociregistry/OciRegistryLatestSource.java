package org.yardship.adapters.out.versionsource.latest.ociregistry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.client.ClientRequestFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yardship.adapters.out.versionsource.auth.BearerAuthFilter;
import org.yardship.adapters.out.versionsource.http.HttpGetResponse;
import org.yardship.adapters.out.versionsource.http.HttpTransport;
import org.yardship.adapters.out.versionsource.http.HttpTransportConfig;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionValue;
import org.yardship.core.ports.out.LatestVersionSource;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
 * skipped unless a {@code prerelease-filter} opts one flavour back in (ADR-0014). An empty or
 * all-skipped tag set throws an {@link IllegalStateException}.
 *
 * <p>Unlike the single-GET latest kinds, one read here is a loop of GETs over the shared
 * {@link HttpTransport} (ADR-0039, ADR-0040), each answered as an {@link HttpGetResponse} this
 * class interprets itself — a {@code 401} is data, not a failure:
 * <ul>
 *   <li><b>Bearer dance (ADR-0013):</b> the anonymous probe of {@code tags/list} may come back
 *       {@code 401 WWW-Authenticate: Bearer realm=…}. The challenge is parsed, a short-lived token
 *       minted from the advertised {@code realm} (echoing {@code service} and {@code scope} verbatim;
 *       falling back to {@code repository:<repo>:pull} only when {@code scope} is absent), and the
 *       tags pages re-fetched carrying {@code Authorization: Bearer <token>}. A token is minted
 *       fresh on every {@link #version()} — no caching. A registry that answers {@code 200} directly
 *       needs no dance.</li>
 *   <li><b>Pagination (ADR-0014):</b> pages are followed through {@code Link: rel="next"} to
 *       completion or until {@link TagSelection#maxTags()} is reached, at which point the largest
 *       tag SEEN is returned with a warning (truncate-and-warn). Each next-page reference is
 *       resolved against the URI the page actually came from ({@link HttpGetResponse#uri()}), so a
 *       first page served via a redirect to a canonical endpoint composes with that endpoint's own
 *       {@code Link}.</li>
 * </ul>
 *
 * <p>The transport is bound to the first {@code tags/list} URL and carries NO configured credential:
 * the realm credential is handed to {@link HttpTransport#get(URI, Optional)} for the mint call only,
 * and the minted {@code Bearer} to the authenticated tags pages only. Redirects, the ADR-0029
 * credential-origin rules and per-app TLS trust ({@code ca-cert}, {@code insecure-skip-tls-verify})
 * apply to every one of those GETs because they share the one transport.
 *
 * <p><b>Exfiltration boundary (ADR-0013):</b> the configured {@code basic} credential is sent to the
 * registry's advertised {@code realm} host, discovered at runtime from the challenge rather than
 * pinned in config.
 */
public class OciRegistryLatestSource implements LatestVersionSource {

    private static final Logger LOG = LoggerFactory.getLogger(OciRegistryLatestSource.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern CHALLENGE_PARAM = Pattern.compile("(\\w+)=\"([^\"]*)\"");
    /** Parses the URL inside {@code <…>} from a {@code Link: <url>; rel="next"} header. */
    private static final Pattern LINK_NEXT_URL = Pattern.compile("<([^>]+)>;\\s*rel=\"next\"");

    private final HttpTransport transport;
    private final String baseUrl;
    private final Optional<ClientRequestFilter> realmCredential;
    private final TagSelection selection;
    private final OciTagSelector tagSelector;

    /**
     * @param baseUrl             {@code https://{registry}/v2/{repo}}, as the factory assembled it
     * @param transportResolution the resolved TLS inputs ({@code ca-cert} / {@code
     *                            insecure-skip-tls-verify}) the factory obtained from
     *                            {@link HttpTransportConfig}; carries no credential — see the
     *                            class javadoc on where each credential goes
     * @param realmCredential     the configured {@code basic} credential for the token realm, if any
     */
    public OciRegistryLatestSource(String baseUrl, HttpTransportConfig.Resolution transportResolution,
                                   Optional<ClientRequestFilter> realmCredential, TagSelection selection,
                                   VersionParser parser) {
        this.baseUrl = baseUrl;
        this.transport = HttpTransport.from(baseUrl + "/tags/list?n=" + selection.pageSize(), transportResolution);
        this.realmCredential = realmCredential;
        this.selection = selection;
        this.tagSelector = new OciTagSelector(selection, parser, baseUrl);
    }

    @Override
    public VersionValue version() {
        HttpGetResponse probe = transport.get();
        if (probe.status() == 200) {
            return paginateAndSelect(probe, Optional.empty());
        }
        if (probe.status() == 401) {
            BearerChallenge challenge = parseChallenge(probe.firstHeader("WWW-Authenticate").orElse(null));
            Optional<ClientRequestFilter> bearer = Optional.of(new BearerAuthFilter(mintToken(challenge)));
            HttpGetResponse firstPage = transport.get(probe.uri(), bearer);
            requireSuccessfulTagsPage(firstPage);
            return paginateAndSelect(firstPage, bearer);
        }
        throw new IllegalStateException("Unexpected HTTP " + probe.status() + " from " + baseUrl + "/tags/list");
    }

    /**
     * The single pagination loop, shared by the anonymous and the authenticated paths; the
     * credential handed in is carried on every further page. Terminates when a page has no
     * {@code Link: rel="next"} (end of the tag set) or when the accumulated tag count reaches
     * {@link TagSelection#maxTags()} while a next link is still present (ADR-0014 truncate-and-warn).
     */
    private VersionValue paginateAndSelect(HttpGetResponse firstPage, Optional<ClientRequestFilter> credential) {
        List<String> allTags = new ArrayList<>();
        HttpGetResponse page = firstPage;
        while (true) {
            allTags.addAll(tagsOf(page));
            Optional<URI> next = nextPageUri(page);
            if (next.isEmpty()) {
                break;
            }
            if (allTags.size() >= selection.maxTags()) {
                LOG.warn("OCI registry tag scan capped at max-tags={} for {} — more pages remain. "
                        + "Returning the largest clean semver from the {} tags seen so far "
                        + "(ADR-0014 truncate-and-warn).", selection.maxTags(), baseUrl, allTags.size());
                break;
            }
            page = transport.get(next.get(), credential);
            requireSuccessfulTagsPage(page);
        }
        return tagSelector.select(allTags);
    }

    /**
     * Reads a {@code tags/list} page body as {@link TagsListDTO}. A {@code null} or absent
     * {@code tags} array is treated as an empty page (a registry may omit the field on an empty
     * last page).
     */
    private static List<String> tagsOf(HttpGetResponse page) {
        try {
            TagsListDTO dto = OBJECT_MAPPER.readValue(page.body(), TagsListDTO.class);
            return dto.tags != null ? dto.tags : List.of();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse OCI tags/list response body: " + page.body(), e);
        }
    }

    /**
     * The absolute URI of the next page, from this page's {@code Link: <url>; rel="next"} header,
     * resolved against the URI the page was fetched from (RFC 8288); empty on the last page.
     */
    private static Optional<URI> nextPageUri(HttpGetResponse page) {
        return page.firstHeader("Link").flatMap(link -> {
            Matcher matcher = LINK_NEXT_URL.matcher(link);
            return matcher.find() ? Optional.of(page.uri().resolve(matcher.group(1))) : Optional.empty();
        });
    }

    /**
     * Guards a page that is EXPECTED to be 2xx (an authenticated page, or any page after the first):
     * a non-2xx fails closed with the status and a truncated body, rather than being handed to the
     * JSON reader, whose failure would name neither. Never applied to the anonymous probe, whose
     * {@code 401} is a legitimate challenge.
     */
    private static void requireSuccessfulTagsPage(HttpGetResponse page) {
        if (!page.isSuccess()) {
            throw new IllegalStateException("HTTP " + page.status() + " response: " + page.truncatedBody());
        }
    }

    /**
     * Mints a bearer token from the realm advertised in {@code challenge}, sending the configured
     * {@code basic} credential (if any) to the realm and nothing otherwise. {@code service} and
     * {@code scope} are echoed verbatim from the challenge as URL-encoded query parameters.
     */
    private String mintToken(BearerChallenge challenge) {
        String query = "service=" + URLEncoder.encode(challenge.service(), StandardCharsets.UTF_8)
                + "&scope=" + URLEncoder.encode(challenge.scope(), StandardCharsets.UTF_8);
        String separator = challenge.realm().contains("?") ? "&" : "?";
        URI realmUri = URI.create(challenge.realm() + separator + query);
        return extractToken(transport.get(realmUri, realmCredential).body());
    }

    /**
     * Parses {@code WWW-Authenticate: Bearer realm="…",service="…"[,scope="…"]} with values echoed
     * verbatim. When {@code scope} is absent, falls back to {@code repository:<repo>:pull} (ADR-0013).
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
            throw new IllegalStateException("Bearer challenge missing 'realm' in: " + wwwAuthenticate);
        }
        String service = params.getOrDefault("service", "");
        String scope = params.containsKey("scope") ? params.get("scope") : fallbackScope();
        return new BearerChallenge(realm, service, scope);
    }

    /** {@code repository:<repo>:pull}, with {@code <repo>} taken from {@code baseUrl}'s {@code /v2/<repo>}. */
    private String fallbackScope() {
        int v2Idx = baseUrl.indexOf("/v2/");
        String repo = v2Idx >= 0 ? baseUrl.substring(v2Idx + 4) : baseUrl;
        return "repository:" + repo + ":pull";
    }

    /** The token from a mint response: {@code token}, else {@code access_token} (ADR-0013). */
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

    /** Parsed fields from a {@code WWW-Authenticate: Bearer} challenge (ADR-0013). */
    private record BearerChallenge(String realm, String service, String scope) {}
}
