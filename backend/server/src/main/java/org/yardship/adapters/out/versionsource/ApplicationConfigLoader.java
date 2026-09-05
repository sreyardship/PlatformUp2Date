package org.yardship.adapters.out.versionsource;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import org.yardship.core.domain.primitives.VersionScheme;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@ConfigMapping(prefix = "platform-config")
public interface ApplicationConfigLoader {
    String scrapeInterval();
    List<AppConfig> apps();

    /**
     * Manual-scrape rolling-window budget. Defaults: at most 10 triggers per 1h sliding window.
     */
    ScrapeTrigger scrapeTrigger();

    /**
     * Targeted-scrape rolling-window budget, separate from {@link #scrapeTrigger()} so
     * agent-driven targeted-scrape work cannot starve the UI's full-refresh budget. Defaults: at most
     * 30 triggers per 1h sliding window (larger than the full-scrape default of 10/1h).
     */
    TargetedScrapeTrigger targetedScrapeTrigger();

    /**
     * Optional GitHub authentication for the scrape's {@code latest} leg. Absent/unset leaves
     * the scrape unauthenticated (60 req/hr); present raises the limit to 5,000 req/hr.
     */
    Github github();

    interface AppConfig {
        /**
         * The app's identity. Declared {@link Optional} (issue 02 / ADR-0032) so SmallRye binding
         * can no longer fail on a missing {@code name} — the config document must still bind as a
         * whole for anything per-app to be rescued. An app that binds with an absent name is
         * dropped from the fleet entirely by {@code VersionSourceResolver}: it cannot be a board
         * row, a {@code configErrors} entry, or a labelled metric series, because reporting an app
         * requires an identity that does not exist. It is counted in the unlabelled
         * {@code pu2d_config_unnamed_apps} metric and gets a line in the aggregate boot report
         * instead. No synthetic or positional name is ever fabricated for it.
         */
        Optional<String> name();
        VersionSource current();
        VersionSource latest();

        /**
         * The version scheme this app's versions are parsed and compared under. Drives the single
         * per-app {@link VersionParser} the resolver builds and threads into both legs. Defaults to
         * {@code semver}, preserving the default behavior for existing apps; SmallRye maps the
         * enum name case-insensitively, so {@code semver} in YAML binds to {@link VersionScheme#SEMVER}.
         */
        @WithDefault("semver")
        VersionScheme versionScheme();

        /**
         * The calendar-version format (calver.org grammar, e.g. {@code YY.0M.MICRO}) this app's
         * versions are parsed against. Required when {@link #versionScheme()} is {@code calver};
         * absent (and ignored) for {@code semver}. Optional at the SmallRye binding level — for a
         * calver app a missing or invalid format is recorded by {@code VersionParsers} as an
         * {@code APP}-scope config error (ADR-0032), degrading both of that app's legs rather than
         * failing the boot (a semver app simply has no calver-format).
         */
        Optional<String> calverFormat();

        /**
         * Optional app-level changelog URL template (ADR-0021), sibling of {@link #versionScheme()}
         * — NOT a {@link VersionSource} field, since the changelog link is a property of the app,
         * not of either version-source leg. Absent leaves {@code changelogUrl} {@code null} on the
         * REST payload; no source kind gets a default template. Placeholder legality (e.g.
         * {@code {version}}, {@code {major}}, or a calver-format-symbol token) is checked at
         * startup by the {@code ChangelogTemplates} wiring bean via {@link
         * org.yardship.core.domain.primitives.ChangelogTemplate}'s constructor; an illegal template
         * is recorded as a {@code CHANGELOG}-scope config error (ADR-0032), which degrades nothing
         * but the link itself.
         */
        Optional<String> changelogUrl();
    }

    /**
     * Tagged version source: a {@code type} discriminator plus the union of type-specific fields.
     * Fields that do not apply to the selected source type are absent.
     */
    interface VersionSource {
        /**
         * The tagged-union discriminator selecting the source kind. Declared {@link Optional}
         * (issue 02 / ADR-0032) so SmallRye binding can no longer fail when {@code type} is
         * absent — its requiredness moves to {@code VersionSourceResolver}, which degrades that
         * side with a clear reason exactly as it already does for an unknown or retired {@code
         * type}: there is no kind to dispatch to either way.
         */
        Optional<String> type();
        Optional<String> url();

        /**
         * Optional path to a PEM file holding a custom certificate authority used to verify the TLS
         * server certificate of the {@code http-json} current source's {@code url}. A transport concern
         * (sibling of {@link #url()}), deliberately NOT under {@link #auth()}. Absent leaves the JVM
         * default trust bundle in place for this app, preserving the default behavior for existing
         * app. When present, the {@code HttpJsonCurrentSourceFactory} reads the PEM once at boot, loads
         * its X.509 certificate(s) into an in-memory truststore and pins it onto THIS app's REST
         * client only ({@code curl --cacert} semantics: replace, not augment) — never a JVM-global
         * truststore. A present-but-blank value, or a path that is missing/unreadable/not parseable as
         * X.509/yields zero certs, is a value-level misconfiguration the factory maps to a
         * {@code FailedCurrentSource}, never a boot crash.
         */
        Optional<String> caCert();

        /**
         * Optional flag read only by the {@code http-json} current source: when {@code true}, the built
         * REST client trusts ANY TLS server certificate and does not verify the server hostname
         * against it — full {@code curl -k} semantics, scoped to THIS app's client only (never a
         * JVM-global TLS setting). A transport concern (sibling of {@link #url()} and
         * {@link #caCert()}). Absent defaults to {@code false}, preserving standard TLS behavior for
         * every existing app. Mutually exclusive with {@link #caCert()}: configuring both is refused
         * by the {@code HttpJsonCurrentSourceFactory} as a value-level misconfiguration (mapped to a
         * {@code FailedCurrentSource}, never a boot crash) rather than silently picking one.
         */
        Optional<Boolean> insecureSkipTlsVerify();

        /**
         * Optional {@code owner/repo} slug read only by the {@code github-release} latest source.
         * The factory builds the full GitHub API URL itself from this value.
         */
        Optional<String> repo();

        /**
         * Optional registry host (e.g. {@code registry.example.com}) read only by the
         * {@code oci-registry} latest source. The factory builds the base URL as
         * {@code https://{registry}/v2/{repo}} by default; an explicit {@code http://} prefix on
         * the value is honoured (useful for local/test registries). Non-blank absence causes the
         * factory to throw at construction time.
         */
        Optional<String> registry();

        /**
         * Optional regular expression read by the {@code http-regex} latest source and, optionally,
         * the {@code http-header} current source. Both apply this pattern via the shared
         * {@code RegexVersionExtractor}, taking <b>capture group 1</b> as each candidate version
         * string (parsed via the app's scheme): {@code http-regex} takes the LARGEST parseable
         * candidate over every match (a latest-leg selection), {@code http-header} takes the FIRST
         * (a current-leg observation is not a selection — ADR-0030). For {@code http-regex} the
         * {@code HttpRegexLatestSourceFactory} validates at boot that it is present; for
         * {@code http-header} it is optional (absent = the raw trimmed header value is parsed
         * directly). Either way, {@code RegexVersionExtractor}, built inside the source, validates
         * that a configured pattern compiles and has at least one capture group. Absent for every
         * other kind.
         */
        Optional<String> regex();

        /**
         * Optional HTTP response header name read only by the {@code http-header} current source.
         * Matched against the wire <b>case-insensitively</b> (RFC 9110 §5.1 — field names are
         * case-insensitive), unlike {@link #versionKey()}'s deliberately case-sensitive JSON
         * Pointer (ADR-0007; JSON object keys ARE case-sensitive by specification). See
         * {@code docs/adr/0030-http-header-current-source.md}. The
         * {@code HttpHeaderCurrentSourceFactory} validates at boot that it is present and
         * non-blank — a structural error, failing boot like {@link #url()} does, not a per-app
         * degradation. Absent for non-{@code http-header} kinds.
         */
        Optional<String> versionHeader();

        Optional<String> namespace();
        Optional<String> workload();
        Optional<String> container();

        /**
         * Optional JSON Pointer (RFC 6901) naming the key the {@code http-json} current source reads
         * the version string from. Absent for non-{@code http-json} kinds and defaults to {@code /version}
         * when absent for {@code http-json}, preserving the legacy {@code {"version":"…"}} contract.
         */
        Optional<String> versionKey();

        /**
         * Optional flag read by the {@code http-json} current source, the {@code k8s-image} current
         * source, and the {@code oci-registry} latest source: when {@code true}, the prerelease
         * segment of the resolved version is cleared before it is reported (e.g.
         * {@code 2.11.1-6b7ecba1} becomes {@code 2.11.1}, {@code 1.23.0-alpine} becomes
         * {@code 1.23.0}). This allows a release carrying a build/commit suffix or a flavour suffix
         * to compare equal to its upstream release instead of ranking below it (ADR-0014).
         *
         * <p>For the {@code oci-registry} latest source, selection and ranking still use the FULL
         * tag value (so {@code 1.24.0-alpine} correctly beats {@code 1.22.0-alpine}); only the
         * REPORTED result is stripped.
         *
         * <p>Absent for non-applicable kinds; defaults to {@code false} when absent, preserving
         * the default behavior of preserving prerelease segments.
         */
        Optional<Boolean> stripPrerelease();

        /**
         * Required (for the {@code http-prometheus} current source) name of the Prometheus metric
         * whose label set carries the version — e.g. {@code blackbox_exporter_build_info}.
         * Matched EXACTLY by {@code PrometheusExposition} (up to {@code {} or whitespace}: a
         * longer metric sharing this name as a prefix is never matched. Absent for every other
         * kind. A missing or blank value is a STRUCTURAL config error for {@code http-prometheus}
         * — the {@code HttpPrometheusCurrentSourceFactory} degrades that app's {@code current}
         * side rather than failing the boot (ADR-0032), matching how {@link #url()} is treated by
         * every current-leg HTTP kind.
         */
        Optional<String> metric();

        /**
         * Optional label name read off the FIRST matching sample of {@link #metric()} by the
         * {@code http-prometheus} current source. Defaults FACTORY-SIDE to {@code version} when
         * absent — as {@code HttpJsonCurrentSourceFactory} defaults {@link #versionKey()} to
         * {@code /version} — deliberately not via {@code @WithDefault}, so the default lives beside
         * the kind's other factory-side defaults rather than in the SmallRye binding. Absent for
         * every other kind.
         */
        Optional<String> versionLabel();

        /**
         * Optional per-app authentication fragment for the {@code http-json} current source (ADR-0008).
         * Absent leaves the request unauthenticated. Username, password, and token values are
         * env-expandable (e.g. {@code ${HARBOR_USER:}}), so an unset variable resolves to a blank
         * value rather than failing to bind at boot. The consuming factory treats missing or blank
         * credentials as value-level misconfiguration.
         */
        Optional<Auth> auth();

        /**
         * Optional page-size fragment read only by the {@code github-release} and
         * {@code oci-registry} latest sources. For {@code github-release} it controls the
         * {@code per_page} query parameter (defaults to 30 when absent; fails fast outside 1–100).
         * For {@code oci-registry} it is the {@code n} query parameter on every
         * {@code tags/list} page request (defaults to 100 when absent; ADR-0014).
         */
        Optional<Integer> pageSize();

        // -----------------------------------------------------------------------
        // SSH os-release source fields (ssh-os-release kind)
        // -----------------------------------------------------------------------

        /**
         * SSH host read only by the {@code ssh-os-release} current source (required for that kind;
         * a blank/absent value fails boot in its factory). Absent for non-ssh kinds. Declared as a
         * plain abstract {@code Optional} getter — like every other field here — so SmallRye binds it
         * from YAML and defaults an absent value to {@link Optional#empty()}. (A {@code default}-bodied
         * method would NOT be bound by {@code @ConfigMapping}.)
         */
        Optional<String> host();

        /** SSH port read only by {@code ssh-os-release} (the factory defaults to 22 when absent). */
        Optional<Integer> port();

        /** SSH user read only by {@code ssh-os-release} (required for that kind; blank/absent fails boot). */
        Optional<String> user();

        /**
         * Inline OpenSSH private key PEM ({@code -----BEGIN OPENSSH PRIVATE KEY-----}) read only by
         * {@code ssh-os-release}. Mutually exclusive with {@link #privateKeyFile()}.
         */
        Optional<String> privateKey();

        /**
         * Path to a file holding the OpenSSH private key PEM; read at connect time (not at source
         * creation) by {@code ssh-os-release}. Mutually exclusive with {@link #privateKey()}.
         */
        Optional<String> privateKeyFile();

        /**
         * Pinned server public key, single line {@code ssh-rsa AAAA…} (no hostname, no comment), read
         * only by {@code ssh-os-release}. Mutually exclusive with {@link #knownHosts()}.
         */
        Optional<String> hostKey();

        /**
         * Path to an OpenSSH {@code known_hosts} file used for host-key verification by
         * {@code ssh-os-release}. Mutually exclusive with {@link #hostKey()}.
         */
        Optional<String> knownHosts();

        /**
         * The field to read from {@code /etc/os-release} (the {@code ssh-os-release} source defaults to
         * {@code VERSION_ID} when absent).
         */
        Optional<String> releaseField();

        /**
         * Optional safety cap on the total number of tags the {@code oci-registry} latest source
         * will accumulate across all pages before stopping pagination. Absent for non-{@code
         * oci-registry} kinds; defaults to 1000 when absent for {@code oci-registry}. On hitting
         * the cap with more pages remaining (a {@code Link: rel="next"} header is still present),
         * the source returns the largest clean semver among the tags SEEN and logs a warning naming
         * the repo and the cap — a deliberate, documented compromise (ADR-0014: truncate-and-warn).
         * A repo whose tags fit within {@code max-tags} is unaffected (no warning, no truncation).
         */
        Optional<Integer> maxTags();

        /**
         * Optional prerelease-variant filter for the {@code oci-registry} latest source (ADR-0014).
         * When absent, prerelease/variant tags are skipped (e.g. {@code 1.22.0-alpine} is not
         * considered); only clean semver tags (no prerelease segment) are eligible. When present,
         * the filter flips the selection to EXACTLY match the prerelease segment: only tags whose
         * semver prerelease segment (dot-joined) equals this string are considered, and the
         * largest among them is reported with its FULL tag value (e.g. {@code 1.22.0-alpine}).
         * EXACT match only: {@code alpine} matches {@code 1.22.0-alpine} but NOT
         * {@code 1.22.0-alpine3.16}. Absent for non-{@code oci-registry} kinds.
         */
        Optional<String> prereleaseFilter();

        /**
         * Tagged auth fragment: a {@code type} discriminator (e.g. {@code basic}) plus the union of
         * scheme-specific credential fields. {@code type()} is declared {@link Optional} (issue 02
         * / ADR-0032) — the last survivor of ADR-0008's retired "malformed structure fails the
         * boot" rule — so a configured {@code auth:} block missing {@code type} binds cleanly and
         * becomes a {@code ConfigError} scoped to the affected side ({@code CURRENT} for the HTTP
         * current-leg kinds via {@code HttpTransportConfig}, {@code LATEST} for {@code
         * oci-registry}) rather than a SmallRye binding failure.
         */
        interface Auth {
            Optional<String> type();

            Optional<String> username();

            Optional<String> password();

            Optional<String> token();

            /**
             * Optional path to a file holding the bearer token, read by {@code FileBearerAuthFilter}
             * on every request. The file is not read at boot — only the path
             * string is validated as non-blank — so a projected Kubernetes serviceaccount token that
             * rotates on disk is always re-read fresh rather than expiring into a 401 storm.
             * Mutually exclusive with {@link #token()}: under {@code auth.type: bearer} exactly one of
             * {@code token}/{@code token-file} may be set.
             */
            Optional<String> tokenFile();
        }
    }

    interface ScrapeTrigger {
        @WithDefault("10")
        int maxPerWindow();

        @WithDefault("1h")
        Duration window();
    }

    /**
     * Same shape as {@link ScrapeTrigger} but with its OWN defaults (30/1h instead of 10/1h):
     * {@code @WithDefault} is resolved per leaf-property, so a method merely returning
     * {@code ScrapeTrigger} again would inherit its 10/1h defaults — a separate interface is needed
     * to default to 30/1h for {@code targeted-scrape-trigger}.
     */
    interface TargetedScrapeTrigger {
        @WithDefault("30")
        int maxPerWindow();

        @WithDefault("1h")
        Duration window();
    }

    interface Github {
        /**
         * Optional GitHub token. Resolved from the environment via SmallRye expansion
         * (e.g. {@code ${GITHUB_TOKEN:}}), so an unset variable yields an empty/absent
         * token rather than a startup failure.
         */
        Optional<String> token();

        /**
         * Optional override for the GitHub API host the {@code github-release} latest source
         * builds its per-app URL against (see ADR-0011). Absent/unset defaults to the real
         * {@code https://api.github.com}; tests override it to point at a local WireMock stub
         * instead of patching the per-app {@code repo} field itself.
         */
        Optional<String> apiBaseUrl();
    }
}
