package org.yardship.adapters.out.versionsource.latest.ociregistry;

import org.yardship.core.domain.exceptions.InvalidVersionException;
import org.yardship.core.domain.primitives.VersionParser;
import org.yardship.core.domain.primitives.VersionValue;

import java.util.List;
import java.util.Optional;

/**
 * Selects the "latest" version from an OCI tag list according to the rules encoded in
 * {@link TagSelection} — largest clean semver wins, prerelease/variant tags are skipped by default,
 * and an optional prerelease-filter opts exactly one flavour back in (ADR-0014).
 *
 * <p>This collaborator is a pure function over a list of tag strings: it performs no I/O and has
 * no state beyond the {@link TagSelection} knobs and the {@link VersionParser} it was constructed
 * with. It is held and called by {@link OciRegistryLatestSource} after pagination has accumulated
 * the full tag set.
 *
 * <p>Public API surface:
 * <ul>
 *   <li>{@link #select(List)} — the single entry point; dispatches internally based on whether a
 *       {@link TagSelection#prereleaseFilter() prereleaseFilter} is configured.</li>
 * </ul>
 */
public class OciTagSelector {

    private final TagSelection selection;
    private final VersionParser parser;
    private final String registryContext;

    public OciTagSelector(TagSelection selection, VersionParser parser, String registryContext) {
        this.selection = selection;
        this.parser = parser;
        this.registryContext = registryContext;
    }

    /**
     * Selects the largest eligible version from the accumulated tag list.
     *
     * <p>When {@link TagSelection#prereleaseFilter()} is present, delegates to the prerelease-filter
     * path which opts exactly one prerelease flavour back in (ADR-0014). When absent, selects the
     * largest CLEAN semver (no prerelease segment).
     *
     * @param tags the full accumulated list of tag strings from the registry
     * @return the selected {@link VersionValue}
     * @throws IllegalStateException when no eligible tag is found
     */
    public VersionValue select(List<String> tags) {
        if (selection.prereleaseFilter().isPresent()) {
            return selectWithPrereleaseFilter(tags, selection.prereleaseFilter().get());
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
     *       not prefix: {@code "alpine"} matches {@code 1.22.0-alpine} but NOT
     *       {@code 1.22.0-alpine3.16}).</li>
     *   <li>Returns the largest among them (by semver precedence) — reporting the FULL tag value by
     *       default, or the stripped version when {@link TagSelection#stripPrerelease()} is true.</li>
     *   <li>Throws when zero tags survive the filter — per-app scrape failure.</li>
     * </ol>
     */
    private VersionValue selectWithPrereleaseFilter(List<String> tags, String filter) {
        VersionValue selected = tags.stream()
                .map(this::tryParseVersion)
                .flatMap(Optional::stream)
                .filter(v -> v.preReleaseSegment().filter(filter::equals).isPresent())
                .reduce((current, candidate) -> current.isOlderThan(candidate) ? candidate : current)
                .orElseThrow(() -> new IllegalStateException(
                        "No tag with prerelease segment '" + filter
                        + "' found in OCI registry tag list for: " + registryContext));
        return selection.stripPrerelease() ? selected.withoutPreRelease() : selected;
    }

    /**
     * Selects the largest clean semver from the accumulated tag list.
     * "Clean" means no prerelease segment (e.g. {@code 1.22.0-alpine} is skipped).
     *
     * @throws IllegalStateException when no clean semver tag is found
     */
    private VersionValue selectLargestCleanVersion(List<String> tags) {
        return tags.stream()
                .map(this::tryParseCleanVersion)
                .flatMap(Optional::stream)
                .reduce((current, candidate) -> current.isOlderThan(candidate) ? candidate : current)
                .orElseThrow(() -> new IllegalStateException(
                        "No clean semver tag found in OCI registry tag list for: " + registryContext));
    }

    private Optional<VersionValue> tryParseVersion(String tag) {
        try {
            return Optional.of(parser.parse(tag));
        } catch (InvalidVersionException ex) {
            return Optional.empty();
        }
    }

    private Optional<VersionValue> tryParseCleanVersion(String tag) {
        return tryParseVersion(tag)
                .filter(v -> v.preReleaseSegment().isEmpty());
    }
}
