# Configuration reference

`platform-config` is the backend's entire monitoring configuration: the scrape
interval, the manual-scrape budgets, and the list of monitored Applications,
each pairing a `current` version source with a `latest` version source.

For a complete working example, see the canonical sample
[`deploy/k8s/base/platform-config.yaml`](../deploy/k8s/base/platform-config.yaml):
the same file both quick starts run, monitoring two real public apps with no
credentials on either side.

The harder keys to get right by hand (an `http-regex` regex, a `version-key`
JSON Pointer, a `calver-format`, a `changelog-url` template) can be tested
before deploying with the [`conf-check` CLI](conf-check.md), which also
validates a whole `platform-config.yaml` in one run for CI gating.

## Top-level keys

| Key | Type | Required | Default | Notes |
|---|---|---|---|---|
| `scrape-interval` | duration string (e.g. `1h`) | yes | — | How often the automatic (lazy) full scrape refreshes the fleet. |
| `scrape-concurrency` | int | no | `15` | Bounds how many apps are scraped in parallel per full scrape. |
| `apps` | list of [App](#app-level-keys) | yes | — | The monitored fleet. |
| `scrape-trigger.max-per-window` | int | no | `10` | Manual full-scrape budget (Refresh button / MCP trigger) — max triggers per rolling window. |
| `scrape-trigger.window` | duration | no | `1h` | Rolling window for `scrape-trigger`. |
| `targeted-scrape-trigger.max-per-window` | int | no | `30` | Targeted-scrape budget (agent-driven single-app/side refresh), kept separate and larger so it cannot starve the UI's full-scrape budget. |
| `targeted-scrape-trigger.window` | duration | no | `1h` | Rolling window for `targeted-scrape-trigger`. |
| `github.token` | string, env-expandable | no | absent (unauthenticated, 60 req/hr) | Optional GitHub token shared by every `github-release` source, raising the limit to 5,000 req/hr. Typically `${GITHUB_TOKEN:}` so an unset env var resolves to empty rather than failing boot. |
| `github.api-base-url` | string | no | `https://api.github.com` | Override for the GitHub API host every `github-release` source builds its URL against — a test/stub seam, not meant for GitHub Enterprise. |

## App-level keys

One entry per monitored Application under `apps[]`:

| Key | Type | Required | Default | Notes |
|---|---|---|---|---|
| `name` | string | yes | — | The Application's identifier, used across every surface (REST, metrics, MCP). |
| `current` | [VersionSource](#version-source-keys-shared) | yes | — | The `current`-side source (`type` selects the kind). |
| `latest` | [VersionSource](#version-source-keys-shared) | yes | — | The `latest`-side source (`type` selects the kind). |
| `version-scheme` | `semver` \| `calver` | no | `semver` | Shared by both legs so they are always commensurable. Case-insensitive. |
| `calver-format` | string (calver.org grammar) | required if `version-scheme: calver`, else ignored | — | e.g. `YY.0M.MICRO`. See [Calver format](#calver-format) below. Validated fail-fast at startup, not by config binding, when calver is declared without one. |
| `changelog-url` | string (template) | no | absent → no changelog link | App-level, sibling of `version-scheme` — not a `VersionSource` field. See [Changelog link templates](#changelog-link-templates). |

## Version source keys (shared)

All source kinds live under the same tagged-union shape (`type` plus a union
of type-specific fields); unused fields are simply absent for a given kind.
The Tier A/B labels in the headings describe how much access a source needs;
the tier model is defined in
[`ARCHITECTURE.md`](../ARCHITECTURE.md#tiers-of-current-probes-ordered-by-required-access).

### `type: http` (current) — Tier A, network-reachable, no credentials required

| Key | Type | Required | Default |
|---|---|---|---|
| `url` | string | yes | — |
| `version-key` | JSON Pointer string | no | `/version` |
| `strip-prerelease` | boolean | no | `false` |
| `ca-cert` | path to PEM file | no | absent → JVM default trust |
| `insecure-skip-tls-verify` | boolean | no | `false` |
| `auth.type` | `basic` \| `bearer` | required if `auth` present | — |
| `auth.username` / `auth.password` | string | required for `auth.type: basic` | — |
| `auth.token` | string | exactly one of `token`/`token-file` required for `auth.type: bearer` | — |
| `auth.token-file` | path | exactly one of `token`/`token-file` required for `auth.type: bearer`; re-read on every request (never cached), so a rotating projected token stays valid | — |

A present-but-blank `ca-cert`, or one that fails to load, shows up as a
failed scrape for that app, not a boot crash. Missing/blank `url` fails
boot.

`insecure-skip-tls-verify` gives full `curl -k` semantics — it skips both
certificate chain validation and hostname verification for that app's REST
client only, never JVM-global TLS state. Enabling it logs a WARN naming the
app's url. It is mutually exclusive with `ca-cert`: configuring both is
refused (a value-level failure, not a boot crash).

### `type: k8s-image` (current) — Tier B, requires cluster access

See [deployment.md](deployment.md) for the ServiceAccount/ClusterRole this needs.

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
violations (both or neither of a pair set) show up as failed scrapes, not
boot crashes.

### `type: github-release` (latest) — no credentials required for public repos

Selects the largest semver release; configured by `owner/repo` slug, not a
raw URL.

| Key | Type | Required | Default |
|---|---|---|---|
| `repo` | `owner/repo` string (exactly one `/`) | yes | — |
| `page-size` | int, 1–100 | no | `30` |

`repo` also draws the (optional, shared) `github.token`/`github.api-base-url`
top-level keys — see [Top-level keys](#top-level-keys).

### `type: oci-registry` (latest) — no credentials required for public repos

Uses the registry's bearer-token challenge dance (a public repo mints an
anonymous token, same as an anonymous `docker pull`), then selects the
largest semver over the full tag set (truncate-and-warn past `max-tags`).

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

| Key | Type | Required | Default |
|---|---|---|---|
| `url` | string | yes | — |
| `regex` | regex with ≥1 capture group | yes | — |

Fetches `url` as text and applies `regex`; capture group 1 of every match is a
candidate version string, the largest wins (validated to compile with at
least one group at startup).

## Surface authentication (MCP + web)

These environment variables are the entire operator-facing contract for the
backend authenticating its own callers as an OAuth 2.1 resource server
(docs/adr/0026, docs/adr/0028). This is *inbound* authentication a protected
surface demands of its callers, distinct from an app's `auth.*` keys above,
which are *outbound* credentials the scraper presents to a version source.
Never call this bare "auth"; see the `MCP endpoint authentication` glossary
entry in `CONTEXT.md`.

`OIDC_ISSUER`/`OIDC_AUDIENCE` are shared by both surfaces (one issuer, one
audience for the whole app); `MCP_OIDC_ROLE` and `WEB_OIDC_ROLE` gate the MCP
(`/api/mcp`) and web (`/api/v1`) surfaces independently of one another: each
is only enforced when its own role var is set.

| Key | Type | Required | Default | Notes |
|---|---|---|---|---|
| `OIDC_ISSUER` | string (issuer URL) | no | absent → surface authentication disabled | Presence is the switch: setting this enables bearer-token validation for the whole app. Unset preserves every surface's open, unauthenticated behavior. |
| `OIDC_AUDIENCE` | string | required when `OIDC_ISSUER` is set | — | Mandatory whenever the issuer is set — boot fails, naming `OIDC_AUDIENCE`, if it is missing. Prevents a token minted for another audience on the same issuer from being replayed against this app. |
| `MCP_OIDC_ROLE` | string (role name) | no | absent → `/api/mcp` stays open | The role a bearer token must carry to reach the MCP endpoint. Presence, not the value, is the per-surface switch; conventionally `pu2d-mcp`. Requires `OIDC_ISSUER` to be set (see the boot-failure case below). |
| `WEB_OIDC_ROLE` | string (role name) | no | absent → `/api/v1` stays open | The role a bearer token must carry to reach the REST API (and, by extension, what the SPA requires after login). Presence, not the value, is the per-surface switch; conventionally `pu2d-web`. Requires `OIDC_ISSUER` to be set (see the boot-failure case below). |

Every boot logs which mode was resolved: `Surface authentication: enabled
against issuer <url>` or `Surface authentication: disabled — protected
surfaces rely on edge/network protection`, so a typo'd variable name is
visible on first boot.

`OIDC_AUDIENCE` set without `OIDC_ISSUER` is not a boot failure (there is
nothing to validate an audience against without an issuer) but is surfaced as
a startup warning, since it is probably a missing or typo'd `OIDC_ISSUER`
rather than an intentional configuration.

**Role-without-issuer is a boot failure.** Either `MCP_OIDC_ROLE` or
`WEB_OIDC_ROLE` set while `OIDC_ISSUER` is absent/blank refuses to boot,
naming `OIDC_ISSUER` in the error. A role requirement is unambiguous
evidence the operator intended auth on, so a role with nothing to validate
against fails loudly instead of silently falling back to disabled. This check is
independent per role var (either one alone trips it) and happens before the
audience check, so an issuer-less config with a role set never gets masked by
the audience error instead.

This section guards the MCP endpoint and the REST API/web UI. `/metrics` and
`/q/health` are never gated by either surface's role; see
[`deployment.md`](deployment.md#web-ui-authentication) for the full boundary
notes and the interim edge-proxy posture for whichever surface you leave
disabled. Everything beyond these four variables (the underlying
`quarkus.oidc.*` properties) is reachable but not documented as contract.

## Frontend runtime configuration

Like `platform-config`, the frontend's configuration is supplied at container
start, not baked into the image. The nginx-based frontend image regenerates
`window._env_` from environment variables via
`docker-entrypoint.d/40-env-config.sh` on every start:

| Key | Type | Required | Default | Notes |
|---|---|---|---|---|
| `API_BASE_URL` | string (base URL) | no | `""` (same-origin) | Where the SPA sends its API calls. Empty means same-origin — the browser calls `/api` on whatever host served the page. |
| `OIDC_AUTHORITY` | string (issuer URL) | no | `""` | The IdP the SPA authenticates against. Must match `OIDC_ISSUER` on the backend. Both `OIDC_AUTHORITY` and `OIDC_CLIENT_ID` must be non-blank for the SPA to enable login; either blank leaves login disabled and no bearer token attached to `/api/v1` calls. |
| `OIDC_CLIENT_ID` | string | no | `""` | The SPA's public (PKCE, no secret) client ID registered in the IdP. |
| `OIDC_SCOPE` | string (space-separated scopes) | no | `openid profile` | Passed as-is to the authorization request. |

See [`deployment.md`](deployment.md#web-ui-authentication) for the full
contract, including IdP prerequisites (public PKCE client,
redirect/end-session URIs, role grants).

## Calver format

`calver-format` is a calver.org grammar string built from these tokens,
separated by `.`, `-`, or `_`: `YYYY`, `YY`, `0Y`, `MM`, `0M`, `WW`, `0W`,
`DD`, `0D`, `MAJOR`, `MINOR`, `MICRO`, `MODIFIER` (e.g. `YY.0M.MICRO`,
`YYYY.MM.DD`). Drift severity groups tokens by category: year-class tokens and
`MAJOR` are MAJOR-severity; sub-year date tokens and `MINOR` are
MINOR-severity; `MICRO`/`MODIFIER` are PATCH-severity.

## Changelog link templates

`changelog-url` is a per-app URL template resolved at read time from the
app's stored latest version; a scrape never observes or stores it. Legal
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
placeholder. A token-free template (a constant URL) is legal.
