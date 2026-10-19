package org.yardship.adapters.out.versionsource.latest.githubrelease;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.yardship.adapters.out.versionsource.VersionFetchException;
import org.yardship.adapters.out.versionsource.http.HttpGetResponse;
import org.yardship.adapters.out.versionsource.http.HttpVersionExtractor;
import org.yardship.core.domain.exceptions.InvalidVersionException;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionValue;

import java.util.List;
import java.util.Optional;

/**
 * The {@code github-release} rule: the final response of {@code GET /repos/{owner}/{repo}/releases}
 * must be a 2xx, its body is decoded as GitHub's release array, and "latest" is the LARGEST version
 * among the releases that are not a prerelease and not a draft, selected by each release's
 * {@code tag_name} — not its time-ordered position and not its {@code name} (ADR-0010). Which
 * releases are in the array is the factory's {@code page-size}; selection is in the app's own
 * {@link VersionParser}, so a calver app picks the largest calendar version.
 *
 * <p>A non-2xx surfaces as a {@link VersionFetchException} carrying the status and a truncated copy
 * of the body, so the per-app scrape loop only has to log {@code e.getMessage()} to get an
 * informative diagnostic (a GitHub rate-limit JSON, for instance). A 2xx whose body is not a release
 * array — a stale {@code /releases/latest} URL returns a single object — fails the same way, naming
 * what could not be parsed.
 */
public final class GithubReleaseVersionExtractor implements HttpVersionExtractor {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<GithubReleaseResponseDTO>> RELEASE_LIST = new TypeReference<>() { };

    private final VersionParser parser;

    public GithubReleaseVersionExtractor(VersionParser parser) {
        this.parser = parser;
    }

    @Override
    public VersionValue extract(HttpGetResponse response) {
        if (!response.isSuccess()) {
            throw new VersionFetchException(
                    "HTTP " + response.status() + " response: " + response.truncatedBody(),
                    response.status(), response.body());
        }
        return parseReleases(response).stream()
                .filter(release -> !release.prerelease && !release.draft)
                .map(this::tryParseVersion)
                .flatMap(Optional::stream)
                .reduce((current, candidate) -> current.isOlderThan(candidate) ? candidate : current)
                .orElseThrow(() -> new IllegalStateException(
                        "No release with a parseable, non-prerelease, non-draft tag_name was found at "
                                + response.uri()));
    }

    private Optional<VersionValue> tryParseVersion(GithubReleaseResponseDTO release) {
        try {
            return Optional.of(parser.parse(release.tagName));
        } catch (InvalidVersionException ex) {
            return Optional.empty();
        }
    }

    private static List<GithubReleaseResponseDTO> parseReleases(HttpGetResponse response) {
        try {
            return OBJECT_MAPPER.readValue(response.body(), RELEASE_LIST);
        } catch (Exception e) {
            throw new VersionFetchException(
                    "Failed to parse GitHub releases response: " + e.getMessage(), response.status(), response.body());
        }
    }
}
