# Configuration reference

`platform-config` is the backend's entire monitoring configuration: the scrape
interval, the manual-scrape budgets, and the list of monitored Applications
(ADR [0001](adr/0001-applications-are-upstream-components.md)), each pairing a
`current` version source with a `latest` version source
(ADR [0005](adr/0005-version-sources-as-pluggable-factories.md)).

This page is derived directly from the code that binds and validates it —
`ApplicationConfigLoader` (the `@ConfigMapping` interface SmallRye Config binds
`platform-config` onto) and the per-source-kind factory that validates its own
fragment at startup. Every key below was found by reading every getter on
`ApplicationConfigLoader.VersionSource`/`AppConfig`/`Auth` and cross-checking
it against the one factory that actually reads it, so this list only contains
keys the loader binds and at least one source consumes.

For a runnable, minimal example, see `docs/samples/platform-config.yaml` (used
by the README quick start). For the *why* behind each source, follow the ADR
links inline.

## Top-level keys

| Key | Type | Required | Default | Notes |
|---|---|---|---|---|
| `scrape-interval` | duration string (e.g. `1h`) | yes | — | How often the automatic (lazy) full scrape refreshes the fleet. |
| `scrape-concurrency` | int | no | `15` | Bounds how many apps are scraped in parallel per full scrape (ADR [0018](adr/0018-scrape-loop-is-bounded-parallel.md)). Read directly via `@ConfigProperty`, not through `ApplicationConfigLoader`. |
| `apps` | list of [App](#app-level-keys) | yes | — | The monitored fleet. |
| `scrape-trigger.max-per-window` | int | no | `10` | Manual full-scrape budget (Refresh button / MCP trigger) — max triggers per rolling window. |
| `scrape-trigger.window` | duration | no | `1h` | Rolling window for `scrape-trigger`. |
| `targeted-scrape-trigger.max-per-window` | int | no | `30` | Targeted-scrape budget (agent-driven single-app/side refresh), kept separate and larger so it cannot starve the UI's full-scrape budget (ADR [0006](adr/0006-targeted-scrape-merges-without-touching-the-fleet-clock.md)). |
| `targeted-scrape-trigger.window` | duration | no | `1h` | Rolling window for `targeted-scrape-trigger`. |
| `github.token` | string, env-expandable | no | absent (unauthenticated, 60 req/hr) | Optional GitHub token shared by every `github-release` source, raising the limit to 5,000 req/hr. Typically `${GITHUB_TOKEN:}` so an unset env var resolves to empty rather than failing boot. |
| `github.api-base-url` | string | no | `https://api.github.com` | Override for the GitHub API host every `github-release` source builds its URL against — a test/stub seam, not meant for GitHub Enterprise (ADR [0011](adr/0011-github-release-configured-by-repo-slug.md)). |

## App-level keys

One entry per monitored Application under `apps[]`:

| Key | Type | Required | Default | Notes |
|---|---|---|---|---|
| `name` | string | yes | — | The Application's identifier, used across every surface (REST, metrics, MCP). |
| `current` | [VersionSource](#version-source-keys-shared) | yes | — | The `current`-side source (`type` selects the kind). |
| `latest` | [VersionSource](#version-source-keys-shared) | yes | — | The `latest`-side source (`type` selects the kind). |
| `version-scheme` | `semver` \| `calver` | no | `semver` | Shared by both legs so they are always commensurable (ADR [0022](adr/0022-version-scheme-authority-is-configuration.md)). Case-insensitive. |
| `calver-format` | string (calver.org grammar) | required if `version-scheme: calver`, else ignored | — | e.g. `YY.0M.MICRO`. See [Calver format](#calver-format) below. Validated fail-fast at startup, not by config binding, when calver is declared without one (ADR [0015](adr/0015-calver-as-a-first-class-version-scheme.md)). |
| `changelog-url` | string (template) | no | absent → no changelog link | App-level, sibling of `version-scheme` — not a `VersionSource` field. See [Changelog link templates](#changelog-link-templates) (ADR [0021](adr/0021-changelog-link-is-a-template-projection.md)). |

## Version source keys (shared)

All source kinds live under the same tagged-union shape (`type` plus a union
of type-specific fields); unused fields are simply absent for a given kind.

### `type: http` (current) — Tier A, network-reachable, no credentials required

ADR [0007](adr/0007-json-pointer-current-version-extraction.md) (JSON Pointer extraction),
[0008](adr/0008-authenticated-http-current-source.md) (auth),
[0012](adr/0012-http-current-source-file-token-and-custom-ca.md) (file token / custom CA).

| Key | Type | Required | Default |
|---|---|---|---|
| `url` | string | yes | — |
| `version-key` | JSON Pointer string | no | `/version` |
| `strip-prerelease` | boolean | no | `false` |
| `ca-cert` | path to PEM file | no | absent → JVM default trust |
| `auth.type` | `basic` \| `bearer` | required if `auth` present | — |
| `auth.username` / `auth.password` | string | required for `auth.type: basic` | — |
| `auth.token` | string | exactly one of `token`/`token-file` required for `auth.type: bearer` | — |
| `auth.token-file` | path | exactly one of `token`/`token-file` required for `auth.type: bearer`; re-read on every request (never cached), so a rotating projected token stays valid | — |

A present-but-blank `ca-cert`, or one that fails to load, is a value-level
failure (`FailedCurrentSource`), never a boot crash. Missing/blank `url` fails
boot.

### `type: k8s-image` (current) — Tier B, requires cluster access

ADR [0005](adr/0005-version-sources-as-pluggable-factories.md). See
[deployment.md](deployment.md) for the ServiceAccount/ClusterRole this needs.

| Key | Type | Required | Default |
|---|---|---|---|
| `namespace` | string | yes | — |
| `workload` | `kind/name` reference, e.g. `deployment/argocd-server` | yes | — |
| `container` | string | yes | — |
| `strip-prerelease` | boolean | no | `false` |

`workload` must be a `kind/name` reference where `kind` is one of
`deployment`, `statefulset`, or `daemonset` (case-insensitive) — e.g.
`deployment/argocd-server`, `statefulset/redis`, `daemonset/node-exporter`.
A value without a `kind/` prefix, or naming any other kind, fails the scrape
for this app with a clear error.

All three of `namespace`/`workload`/`container` are required and non-blank;
missing any one fails boot. The Kubernetes client itself needs no
configuration here — it auto-configures in-cluster from the backend's
ServiceAccount token.

### `type: ssh-os-release` (current) — Tier B, requires SSH access

ADR [0016](adr/0016-ssh-os-release-current-source.md),
[0025](adr/0025-ssh-os-release-native-image-reachability.md) (native-image
reachability).

| Key | Type | Required | Default |
|---|---|---|---|
| `host` | string | yes | — |
| `port` | int | no | `22` |
| `user` | string | yes | — |
| `private-key` | inline OpenSSH PEM | exactly one of `private-key`/`private-key-file` required | — |
| `private-key-file` | path, read at connect time | exactly one of `private-key`/`private-key-file` required | — |
| `host-key` | single-line `ssh-ed25519 AAAA…` | exactly one of `host-key`/`known-hosts` required (no trust-on-first-use) | — |
| `known-hosts` | path to `known_hosts` file | exactly one of `host-key`/`known-hosts` required | — |
| `release-field` | string | no | `VERSION_ID` |

Blank/absent `host` or `user` fails boot; the mutually-exclusive-pair
violations (both or neither of a pair set) are value-level failures
(`FailedCurrentSource`), not boot crashes.

### `type: github-release` (latest) — no credentials required for public repos

ADR [0010](adr/0010-github-release-latest-is-largest-semver.md) (largest-semver
selection), [0011](adr/0011-github-release-configured-by-repo-slug.md) (`repo`
slug, not `url`).

| Key | Type | Required | Default |
|---|---|---|---|
| `repo` | `owner/repo` string (exactly one `/`) | yes | — |
| `page-size` | int, 1–100 | no | `30` |

`repo` also draws the (optional, shared) `github.token`/`github.api-base-url`
top-level keys — see [Top-level keys](#top-level-keys).

### `type: oci-registry` (latest) — no credentials required for public repos

ADR [0013](adr/0013-oci-registry-bearer-challenge-dance.md) (bearer-token
dance; a public repo mints an anonymous token, same as an anonymous
`docker pull`), [0014](adr/0014-oci-registry-latest-is-largest-semver-over-full-tag-set.md)
(largest-semver over the full tag set, truncate-and-warn).

| Key | Type | Required | Default |
|---|---|---|---|
| `registry` | host, e.g. `registry-1.docker.io` (an explicit `http://`/`https://` prefix is honoured, e.g. for a local test registry) | yes | — |
| `repo` | string | yes | — |
| `page-size` | int | no | `100` |
| `max-tags` | int | no | `1000` |
| `prerelease-filter` | string | no | absent → only clean semver tags (no prerelease segment) are eligible |
| `strip-prerelease` | boolean | no | `false` |
| `auth.type` | `basic` (only) | no | absent → anonymous | 
| `auth.username` / `auth.password` | string | required if `auth` present | — |

The base URL is assembled as `https://{registry}/v2/{repo}` (or `http://` if
`registry` already carries a scheme). `bearer`/`token-file` auth are not
supported here — they don't fit the realm-mint flow.

### `type: http-regex` (latest) — no credentials required

ADR [0017](adr/0017-http-regex-latest-source.md).

| Key | Type | Required | Default |
|---|---|---|---|
| `url` | string | yes | — |
| `regex` | regex with ≥1 capture group | yes | — |

Fetches `url` as text and applies `regex`; capture group 1 of every match is a
candidate version string, the largest wins (validated to compile with at
least one group at startup).

## Calver format

`calver-format` is a calver.org grammar string built from these tokens,
separated by `.`, `-`, or `_`: `YYYY`, `YY`, `0Y`, `MM`, `0M`, `WW`, `0W`,
`DD`, `0D`, `MAJOR`, `MINOR`, `MICRO`, `MODIFIER` (e.g. `YY.0M.MICRO`,
`YYYY.MM.DD`). Drift severity groups tokens by category: year-class tokens and
`MAJOR` are MAJOR-severity; sub-year date tokens and `MINOR` are
MINOR-severity; `MICRO`/`MODIFIER` are PATCH-severity. See ADR
[0015](adr/0015-calver-as-a-first-class-version-scheme.md).

## Changelog link templates

`changelog-url` is a per-app URL template resolved at read time from the
app's stored latest version — never observed or stored by a scrape. Legal
placeholders:

- `{version}` — the full version string; legal for both schemes.
- `{major}` / `{minor}` / `{patch}` — legal only for `version-scheme: semver`.
- A calver.org format-symbol token declared in the app's `calver-format`
  (e.g. `{YY}`, `{0M}`, `{MICRO}`) — legal only for `version-scheme: calver`,
  and only for tokens the app's own format actually declares. Values are the
  *displayed* substrings of the matched version string (zero-padding
  preserved), never re-rendered numbers.

An illegal placeholder (wrong scheme, or a calver token absent from the app's
declared format) fails boot with a message naming the offending app and
placeholder. A token-free template (a constant URL) is legal. See ADR
[0021](adr/0021-changelog-link-is-a-template-projection.md).
