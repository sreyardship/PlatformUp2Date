package org.yardship.adapters.out.versionsource.latest.ociregistry;

import java.util.Optional;

/**
 * Groups the tag-selection knobs for {@link OciRegistryLatestSource} into a single value object,
 * replacing the old positional constructor arguments.
 *
 * @param pageSize         the {@code n} query parameter on every {@code tags/list} page request
 *                         (ADR-0014 default: 100)
 * @param maxTags          safety cap on the total tags accumulated across all pages before stopping
 *                         pagination (ADR-0014 default: 1000; truncate-and-warn on cap exceeded)
 * @param prereleaseFilter when present, opts exactly one prerelease flavour back in: only tags
 *                         whose semver prerelease segment (dot-joined) equals this string are
 *                         eligible, and the largest is selected by full-tag value. When absent,
 *                         only clean semver tags (no prerelease segment) are eligible.
 * @param stripPrerelease  when {@code true}, the prerelease segment of the SELECTED tag is cleared
 *                         before it is reported (e.g. {@code 1.22.0-alpine} → {@code 1.22.0}).
 *                         Selection and ranking still use the FULL tag value — only the reported
 *                         result is stripped. Also honoured by the {@code k8s-image} current source
 *                         for the same stripping purpose.
 */
public record TagSelection(
        int pageSize,
        int maxTags,
        Optional<String> prereleaseFilter,
        boolean stripPrerelease) {
}
