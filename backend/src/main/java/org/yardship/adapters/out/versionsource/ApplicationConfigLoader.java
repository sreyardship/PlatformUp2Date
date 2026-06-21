package org.yardship.adapters.out.versionsource;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

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
         * Optional {@code owner/repo} slug read only by the {@code github-release} latest source.
         * The factory builds the full GitHub API URL itself from this value.
         */
        Optional<String> repo();

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
         * Optional flag read only by the {@code http} current source: when {@code true}, the
         * prerelease segment of the version read from the upstream endpoint is cleared (e.g.
         * {@code 2.11.1-6b7ecba1} becomes {@code 2.11.1}) so a release carrying a build/commit
         * suffix compares equal to its upstream release instead of ranking below it. Absent for
         * non-{@code http} kinds and defaults to {@code false} when absent for {@code http},
         * preserving today's behaviour (prerelease preserved) for every existing app.
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
         * Optional page-size fragment read only by the {@code github-release} latest source
         * (issue: largest-semver-across-recent-releases). Controls how many of the most-recently
         * published releases are requested from {@code GET /releases} via the {@code per_page}
         * query parameter. Absent for non-{@code github-release} kinds; the
         * {@code GithubReleaseLatestSourceFactory} defaults it to 30 when absent and fails fast in
         * {@code create()} for a value outside GitHub's {@code per_page} range of 1–100.
         */
        Optional<Integer> pageSize();

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
