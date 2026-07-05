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
     * Targeted-scrape rolling-window budget (issue 03) — separate from {@link #scrapeTrigger()} so
     * agent-driven targeted-scrape work cannot starve the UI's full-Refresh budget. Defaults: at most
     * 30 triggers per 1h sliding window (larger than the full-scrape default of 10/1h).
     */
    TargetedScrapeTrigger targetedScrapeTrigger();

    /**
     * Optional GitHub authentication for the scrape's {@code latest} leg. Absent/unset leaves
     * the scrape unauthenticated (60 req/hr); present raises the limit to 5,000 req/hr.
     */
    Github github();

    interface AppConfig {
        String name();
        VersionSource current();
        VersionSource latest();

        /**
         * The version scheme this app's versions are parsed and compared under. Drives the single
         * per-app {@link VersionParser} the resolver builds and threads into both legs. Defaults to
         * {@code semver}, preserving today's behaviour for every existing app; SmallRye maps the
         * enum name case-insensitively, so {@code semver} in YAML binds to {@link VersionScheme#SEMVER}.
         */
        @WithDefault("semver")
        VersionScheme versionScheme();

        /**
         * The calendar-version format (calver.org grammar, e.g. {@code YY.0M.MICRO}) this app's
         * versions are parsed against. Required when {@link #versionScheme()} is {@code calver};
         * absent (and ignored) for {@code semver}. Optional at the SmallRye binding level — the
         * requiredness for calver apps is enforced fail-fast when the per-app {@link VersionParser}
         * is built in the resolver, not by config binding (a semver app simply has no calver-format).
         */
        Optional<String> calverFormat();

        /**
         * Optional app-level changelog URL template (ADR-0021), sibling of {@link #versionScheme()}
         * — NOT a {@link VersionSource} field, since the changelog link is a property of the app,
         * not of either version-source leg. Absent leaves {@code changelogUrl} {@code null} on the
         * REST payload; no source kind gets a default template. Placeholder legality (e.g.
         * {@code {version}}, {@code {major}}, or a calver-format-symbol token) is validated
         * fail-fast at startup by the {@code ChangelogTemplates} wiring bean via {@link
         * org.yardship.core.domain.primitives.ChangelogTemplate}'s constructor.
         */
        Optional<String> changelogUrl();
    }

    /**
     * Tagged version source: a {@code type} discriminator plus the union of type-specific fields.
     * Today only the {@code url} of the {@code http} (current) and {@code github-release} (latest)
     * types is read; the {@code namespace}/{@code workload}/{@code container} fields are reserved
     * for the Kubernetes source added in a later slice and are absent for http/github apps.
     */
    interface VersionSource {
        String type();
        Optional<String> url();

        /**
         * Optional path to a PEM file holding a custom certificate authority used to verify the TLS
         * server certificate of the {@code http} current source's {@code url}. A transport concern
         * (sibling of {@link #url()}), deliberately NOT under {@link #auth()}. Absent leaves the JVM
         * default trust bundle in place for this app, preserving today's behaviour for every existing
         * app. When present, the {@code HttpCurrentSourceFactory} reads the PEM once at boot, loads
         * its X.509 certificate(s) into an in-memory truststore and pins it onto THIS app's REST
         * client only ({@code curl --cacert} semantics: replace, not augment) — never a JVM-global
         * truststore. A present-but-blank value, or a path that is missing/unreadable/not parseable as
         * X.509/yields zero certs, is a value-level misconfiguration the factory maps to a
         * {@code FailedCurrentSource}, never a boot crash.
         */
        Optional<String> caCert();

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
         * Optional regular expression read only by the {@code http-regex} latest source. The source
         * fetches {@link #url()} as text and applies this pattern, taking <b>capture group 1</b> of
         * every match as a candidate version string (parsed via the app's scheme; the largest wins).
         * The {@code HttpRegexLatestSourceFactory} validates at boot that it is present, compiles, and
         * has at least one capture group. Absent for non-{@code http-regex} kinds.
         */
        Optional<String> regex();

        Optional<String> namespace();
        Optional<String> workload();
        Optional<String> container();

        /**
         * Optional JSON Pointer (RFC 6901) naming the key the {@code http} current source reads
         * the version string from. Absent for non-{@code http} kinds and defaults to {@code /version}
         * when absent for {@code http}, preserving the legacy {@code {"version":"…"}} contract.
         */
        Optional<String> versionKey();

        /**
         * Optional flag read by the {@code http} current source, the {@code k8s-image} current
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
         * today's behaviour (prerelease preserved) for every existing app.
         */
        Optional<Boolean> stripPrerelease();

        /**
         * Optional per-app authentication fragment for the {@code http} current source (Harbor case
         * study, issue 02; see ADR-0008). Absent leaves the request unauthenticated, preserving
         * today's behaviour for every existing app. Username/password/token are env-expandable
         * (e.g. {@code ${HARBOR_USER:}}), so an unset variable resolves to an empty/blank value
         * rather than failing to bind at boot; the factory that consumes this fragment is
         * responsible for treating a missing/blank credential as a value-level misconfiguration. The
         * {@code token} field is reserved for the bearer scheme added in a later slice.
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
         * Tagged auth fragment: a required {@code type} discriminator (e.g. {@code basic}) plus the
         * union of scheme-specific credential fields. {@code type()} is intentionally a bare
         * (non-Optional) leaf — like {@link VersionSource#type()} itself — so a configured
         * {@code auth:} block missing {@code type} fails SmallRye binding at boot rather than
         * surfacing as a confusing runtime value error.
         */
        interface Auth {
            String type();

            Optional<String> username();

            Optional<String> password();

            Optional<String> token();

            /**
             * Optional path to a file holding the bearer token, read by {@code FileBearerAuthFilter}
             * on EVERY request (issue 01: token-file). The file is NOT read at boot — only the path
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
