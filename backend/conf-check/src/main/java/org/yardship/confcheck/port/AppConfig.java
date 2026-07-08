package org.yardship.confcheck.port;

import org.yardship.core.domain.primitives.VersionScheme;

import java.util.Optional;

/**
 * A single app's slice of a {@code platform-config.yaml}, lightweight and CLI-owned (issue 06):
 * a pure value record carrying only the fields the {@code config} gate's validators need.
 * Mirrors the shape of the backend's {@code ApplicationConfigLoader.AppConfig}/{@code VersionSource}
 * (see {@code backend/src/main/java/org/yardship/adapters/out/versionsource/ApplicationConfigLoader.java})
 * but is NOT bound by that Quarkus/SmallRye-specific {@code @ConfigMapping} interface — {@code :cli}
 * must not depend on {@code :backend}.
 *
 * <p>Deliberately does not model every field {@code ApplicationConfigLoader} exposes: auth
 * fragments, {@code repo}/{@code registry} (github-release/oci-registry), and the entire
 * {@code ssh-os-release}/{@code k8s-image} field set are omitted, because none of those source
 * kinds have a CLI-transparent validator to run against them for this issue — an app configured
 * with only those kinds simply has "nothing applicable to check" for the corresponding surface
 * (see {@code ConfigFileValidation}), not a parse failure.
 *
 * <p><b>Design note — {@code currentVersionKey}'s default:</b> the backend's {@code http} current
 * source defaults an absent {@code version-key} to {@code "/version"} at CONSUMPTION time (see
 * {@code HttpCurrentSource}), not at config-binding time (SmallRye's {@code Optional<String>}
 * leaves it genuinely absent when unset). This record mirrors that consumption-time default
 * boundary: {@link #currentVersionKey()} reports exactly what the YAML said (possibly absent).
 * {@code YamlAppConfigReader} does not fabricate a default value into this field; the default is
 * applied by {@code ConfigFileValidation} itself when it decides whether the pointer surface is
 * applicable and what pointer to use — the same place {@code PointerCommand} would apply it were
 * a user driving {@code cli pointer} by hand with no {@code --pointer} override. Keeping the
 * default out of the reader keeps {@code AppConfig} a faithful, non-lossy transcription of the
 * YAML, and keeps the "what counts as configured" decision in one place (the use case).
 *
 * @param name                  the app's {@code name}.
 * @param versionScheme         the app's {@code version-scheme}; defaults to {@link VersionScheme#SEMVER}
 *                               when the YAML omits it (mirrors {@code ApplicationConfigLoader}'s
 *                               {@code @WithDefault("semver")}) — this default IS applied by the reader,
 *                               since {@code version-scheme} has no "was it configured" ambiguity the
 *                               way {@code version-key} does (semver is always a legal fallback).
 * @param calverFormat          the app's {@code calver-format}; present only when configured (required,
 *                               by convention, when {@code versionScheme} is {@link VersionScheme#CALVER},
 *                               but this record does not itself enforce that — validators do).
 * @param changelogUrl          the app's {@code changelog-url} template, when configured.
 * @param currentType           {@code current.type} (e.g. {@code "http"}, {@code "ssh-os-release"},
 *                               {@code "k8s-image"}); never null — required by the real schema.
 * @param currentUrl            {@code current.url}, when configured (present for {@code http}).
 * @param currentVersionKey     {@code current.version-key}, EXACTLY as configured — absent means
 *                               "not configured in YAML", not "defaults to /version"; see the
 *                               design note above for where that default is actually applied.
 * @param currentStripPrerelease {@code current.strip-prerelease}; defaults to {@code false} when absent,
 *                               mirroring the backend's {@code Optional<Boolean>} + factory default.
 * @param latestType            {@code latest.type} (e.g. {@code "http-regex"}, {@code "github-release"},
 *                               {@code "oci-registry"}); never null — required by the real schema.
 * @param latestUrl             {@code latest.url}, when configured (present for {@code http-regex}).
 * @param latestRegex           {@code latest.regex}, when configured (present for {@code http-regex}).
 */
public record AppConfig(
        String name,
        VersionScheme versionScheme,
        Optional<String> calverFormat,
        Optional<String> changelogUrl,
        String currentType,
        Optional<String> currentUrl,
        Optional<String> currentVersionKey,
        boolean currentStripPrerelease,
        String latestType,
        Optional<String> latestUrl,
        Optional<String> latestRegex) {
}
