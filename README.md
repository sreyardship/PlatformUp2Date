# <img src="frontend/public/logo.svg" alt="PlatformUp2Date logo" align="absmiddle" height="45"/> PlatformUp2Date

You run a platform, a home lab or just a pile of apps. They're all out of date... let's change that!

If it exposes its version somehow: an HTTP endpoint, a container image tag, `ssh`/`os-release`, a
Prometheus metric[^prom-source], its version drift can be monitored!

[^prom-source]: Reading `current` from a Prometheus metric is not implemented yet. But just you wait! It was a great idea, i just don't see where it would be used, so ehrm... Make an issue if you have a real world use for it? Or a PR <3.

PlatformUp2Date monitors deployed applications against their latest upstream
releases and shows, per app, whether it's up-to-date or behind. "Current" is
always the app's actually-observed running state, never a declared value
like a GitOps repo pin. "Latest" comes from an independent upstream source
such as GitHub Releases or a container registry's tag list. (See
[`ARCHITECTURE.md`](ARCHITECTURE.md) for why that distinction matters.)

Monitor version drift for

- Kubernetes deployments
- Docker Compose images
- VMs
- Bare-metal machines
- OpenWrt routers
- Random services you have no control over, purely to demonstrate your superiority at staying on top of things
- Coffee makers?


It integrates with Prometheus/Grafana and ships its own frontend — deploy it
or don't :) It also serves an [MCP endpoint](#mcp-endpoint), so an AI agent
can ask what's outdated and read the changelogs while helping you upgrade.

[![edge](https://github.com/sreyardship/PlatformUp2Date/actions/workflows/edge.yml/badge.svg)](https://github.com/sreyardship/PlatformUp2Date/actions/workflows/edge.yml)
[![release](https://img.shields.io/github/v/release/sreyardship/PlatformUp2Date)](https://github.com/sreyardship/PlatformUp2Date/releases/latest)
[![license](https://img.shields.io/github/license/sreyardship/PlatformUp2Date)](LICENSE)

![The board: every monitored app with its current and latest version, color-coded by drift status](docs/img/board.png)

## Quick taste (no toolchain required)

All you need is Docker. This pulls the published images from GHCR instead of
building anything locally:

```bash
git clone https://github.com/sreyardship/PlatformUp2Date.git
cd PlatformUp2Date
docker compose -f compose.quickstart.yml up
```

Then open [localhost:3000](http://localhost:3000). Within a few seconds you
should see two application rows:

- **mastodon** — `current` is read from the public
  [chaos.social](https://chaos.social) instance's API (a genuinely observed
  running version, not a repo pin); `latest` comes from GitHub Releases.
  chaos.social upgrades conservatively, so this card is usually *behind* —
  showing what drift detection looks like: a red/orange card with the
  current and latest versions side by side, and a changelog link to the
  release it's missing.
- **gitea** — `current` is read from the public
  [gitea.com](https://gitea.com) SaaS instance's version API; `latest` comes
  from Gitea's official image tags on Docker Hub. gitea.com tracks upstream
  closely, so this card is usually *green* (up to date).

Both instances are live third-party services, so the exact colors on any
given day depend on what they happen to be running. Both apps resolve
without any credentials — see
[`deploy/k8s/base/platform-config.yaml`](deploy/k8s/base/platform-config.yaml) for
the exact config, and [`docs/configuration.md`](docs/configuration.md) for
every source type and config key available for your own apps.

## Documentation

- [`docs/configuration.md`](docs/configuration.md) — every version-source
  type, every config key, version schemes, changelog-link templates.
- [`docs/conf-check.md`](docs/conf-check.md) — the `conf-check` CLI: test a
  regex, JSON Pointer, calver format, or changelog template while writing
  config, and gate a whole `platform-config.yaml` in CI before it ships.
- [`docs/deployment.md`](docs/deployment.md) — running this for real:
  Kubernetes (the primary target, though any OCI runtime works), HA and
  replicas, Valkey, RBAC for the `k8s-image` source, Prometheus scraping,
  alert rules, the Grafana dashboard.
- [`RELEASE.md`](RELEASE.md) — image channels (`edge`, `sha-<short>`, semver),
  what each CI trigger publishes, and how a release is cut.
- [`ARCHITECTURE.md`](ARCHITECTURE.md) — the design principles behind the
  substrate-agnostic version-source model.
- [`CONTEXT.md`](CONTEXT.md) — glossary of project-specific terms.
- [`docs/adr/`](docs/adr/) — architecture decision records for individual
  design choices.

## Metrics & Alerting

The backend exposes a Prometheus scrape endpoint at `/metrics`. Four metric families are exported:
`pu2d_version_drift_level`, `pu2d_application_info`, `pu2d_scrape_last_success_timestamp_seconds`,
and `pu2d_scrape_last_failure_timestamp_seconds` — see [`docs/deployment.md`](docs/deployment.md#metrics)
for the full list. The core one for version monitoring is a gauge:

```
# HELP pu2d_version_drift_level How far the deployed version is behind latest (0=current, 1=patch, 2=minor, 3=major)
# TYPE pu2d_version_drift_level gauge
pu2d_version_drift_level{app="argo-cd"} 3
pu2d_version_drift_level{app="git-tea"} 0
```

One gauge answers both questions: whether an app is outdated, and how far behind it
is — the value is the highest-significance difference between the deployed and latest
version. For semver that's the changed component; two caveats: pre-release differences
report as `1`, and a difference in build metadata only is ignored and reports as `0`.
Calver apps (e.g. Ubuntu's `24.04`) report on the same scale, graded by what the
changed token *means*: a changed year is `3`, month/week/day `2`, micro/modifier `1` —
see [`docs/configuration.md`](docs/configuration.md#calver-format). `pu2d_application_info`
carries the actual current/latest version strings and covers every configured app,
including ones that haven't resolved yet.

For more detail than the gauges carry, use the frontend or the
`GET /api/v1/version` endpoint. For scraping setup, alert rules, and the bundled
Grafana dashboard on a real cluster, see [`docs/deployment.md`](docs/deployment.md).

![The bundled Grafana dashboard: fleet stats and a per-app drift table](docs/img/grafana-dashboard.png)

## MCP endpoint

The backend exposes a [Model Context Protocol](https://modelcontextprotocol.io/) server
so an AI agent can ask "which monitored applications are out of date, how far behind,
and what changed between the versions?" without any custom integration. It is a third read surface over the same data as `/metrics`
and `GET /api/v1/version` — any MCP-capable host can connect and discover the tools at
runtime.

Transport is **Streamable HTTP**, served at `/api/mcp` on the backend's HTTP port. It runs
stateless (each request auto-initialises its own throwaway session), so the endpoint works
behind multiple replicas with no session affinity.

### Tools

- **`list_outdated_applications(minSeverity?)`** — returns the applications running behind
  their latest upstream release, each as
  `{ name, current, latest, outdated, drift, changelogUrl, … }` where `drift` is
  `PATCH` / `MINOR` / `MAJOR`. The optional `minSeverity` argument
  (`PATCH` | `MINOR` | `MAJOR`) filters by how far behind an app is; omit it to get every
  app with any drift.
- **`get_application(name)`** — returns the status of a single application by exact name,
  or nothing if it isn't monitored.

`changelogUrl` links the release notes of the latest upstream release — if the app has a
[changelog-link template](docs/configuration.md#changelog-link-templates) configured,
`null` otherwise — so an agent can read what changed (breaking changes included) before
starting update work.

The drift verdict is computed server-side by the same tested semver logic that backs the
metrics gauge, so the caller never has to compare versions itself.

### Connecting a client

Point any MCP client that supports remote Streamable HTTP servers at:

```
https://platformup2date.example.com/api/mcp
```

Most MCP-capable CLIs have a built-in subcommand for registering a remote HTTP
server (consult your client's docs); the shape is generally
`<your-mcp-client> mcp add --transport http platformup2date <url>`.

### MCP endpoint authentication

The MCP endpoint, the web UI and the REST API (`/api/v1`) all authenticate against
one shared issuer, each gated independently by its own role. Set the shared pair
on the backend, plus whichever surface's role you want enforced:

```
OIDC_ISSUER=https://auth.example.com/realms/yourrealm
OIDC_AUDIENCE=platformup2date
MCP_OIDC_ROLE=pu2d-mcp
```

and register the URL with any MCP client:

```
https://platformup2date.example.com/api/mcp
```

A client that supports the MCP authorization spec discovers the issuer from the
RFC 9728 protected-resource metadata the endpoint publishes, and logs in natively —
no bearer token to fetch or attach by hand. `OIDC_ISSUER`/`OIDC_AUDIENCE` are
required together (issuer unset leaves every surface exactly as before,
unauthenticated; issuer set without audience refuses to boot, naming the missing
variable). `MCP_OIDC_ROLE` is what actually switches the MCP surface on — it
defaults to `pu2d-mcp` in docs/deploy manifests but is only enforced when the
variable is *set*; unset, `/api/mcp` stays open even with the issuer/audience
configured. Granting `pu2d-mcp` (or whatever value you set) to a user in the IdP
is what admits them as an MCP caller. See
[`docs/configuration.md`](docs/configuration.md#surface-authentication-mcp--web) for the
full contract.

> **DCR caveat.** Native discovery leans on your issuer supporting *dynamic client
> registration*. If it doesn't, enable DCR or pre-register the client IDs your MCP
> clients use — otherwise the flow dies mysteriously at registration, which is your
> IdP's concern, not this app's.

### Web UI authentication

The web UI and REST API (`/api/v1`) authenticate against the same shared issuer,
gated by their own role var, `WEB_OIDC_ROLE` (default `pu2d-web`). Same rules as
MCP: the issuer/audience pair must be set, and the role var is what actually
switches the web surface on:

```
OIDC_ISSUER=https://auth.example.com/realms/yourrealm
OIDC_AUDIENCE=platformup2date
WEB_OIDC_ROLE=pu2d-web
```

Unlike MCP, the backend alone isn't enough — the React SPA itself has to become
an OIDC client. Point it at the same IdP with two runtime variables consumed by
the frontend container at start (`window._env_`, injected by
`docker-entrypoint.d/40-env-config.sh`, the same mechanism `API_BASE_URL` already
uses):

```
OIDC_AUTHORITY=https://auth.example.com/realms/yourrealm
OIDC_CLIENT_ID=platformup2date-web
```

`OIDC_SCOPE` is optional and defaults to `openid profile`. Both `OIDC_AUTHORITY`
and `OIDC_CLIENT_ID` must be present for the SPA to enable auth; leaving either
blank renders the app exactly as it does today — no login, no bearer token
attached to `/api/v1` calls.

The SPA runs Authorization Code + PKCE (`oidc-client-ts` / `react-oidc-context`),
holds the access token in memory only (never `localStorage`/`sessionStorage`),
and silently renews it in the background. A caller whose token lacks the
`pu2d-web` role gets a distinct *Not authorized* screen (a 403 from the backend,
not a login failure) with a log-out action.

**Boundary notes:**

- **Enabling web auth gates `/api/v1`, not the SPA static shell.** An anonymous
  visitor still loads the page — the JS bundle has to run before it can redirect
  anyone anywhere — and is then redirected to the IdP to log in. A BFF or edge
  proxy could hide the page itself; this topology structurally cannot, by design
  (see [ADR 0028](docs/adr/0028-web-and-mcp-surfaces-role-gated-behind-one-issuer.md)).
- **`/metrics`, `/q/health`, and the OpenAPI spec (`/q/openapi`) stay open**
  regardless of web-auth state — none of them live under `/api/v1`.
- **No ingress change is needed for web auth**, unlike MCP. The SPA discovers the
  IdP directly from its own runtime config (`OIDC_AUTHORITY`); there's no
  server-side protected-resource metadata document to route to, so the
  `/.well-known/oauth-protected-resource` HTTPRoute rule MCP needs has no web
  equivalent.
- **Dev mode needs CORS** — the Vite dev server (`localhost:3000`) calls the
  backend (`localhost:8080`) cross-origin; the `%dev`-scoped CORS block in
  `application.yml` allows the `Authorization` header for exactly that origin.
  Production is same-origin behind `/api`, so no CORS is needed there.

**IdP prerequisites.** Register the SPA as a **public client with PKCE**
(no client secret — it can't keep one), with:

- a redirect URI of the SPA's own origin, `/` (e.g. `https://platformup2date.example.com/`)
- a matching post-logout / end-session redirect URI, since log-out ends the IdP
  session (RP-initiated logout), not just the local token
- silent-renew (refresh) enabled/expected, so the session survives a reload
  without a full-page redirect
- the `pu2d-web` role (or whatever value you set `WEB_OIDC_ROLE` to) granted as a
  realm/group role to whichever users should reach the app — granting the role
  is what actually admits a caller, independent of whether they can authenticate
  against the IdP at all

See [`docs/deployment.md`](docs/deployment.md#web-ui-authentication) for the
cluster-level implications (and the contrast with MCP's ingress rule) and
[`docs/configuration.md`](docs/configuration.md) for the full variable reference.

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md)
