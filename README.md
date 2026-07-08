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

The MCP endpoint can authenticate its own callers directly, as an OAuth 2.1 resource
server — no wrapper script, no interactive login proxied through the app. Set two
environment variables on the backend:

```
MCP_OIDC_ISSUER=https://auth.example.com/realms/yourrealm
MCP_OIDC_AUDIENCE=platformup2date-mcp
```

and register the URL with any MCP client:

```
https://platformup2date.example.com/api/mcp
```

A client that supports the MCP authorization spec discovers the issuer from the
RFC 9728 protected-resource metadata the endpoint publishes, and logs in natively —
no bearer token to fetch or attach by hand. Both variables are required together:
`MCP_OIDC_ISSUER` unset leaves the endpoint unauthenticated; set
without `MCP_OIDC_AUDIENCE` it refuses to boot, naming the missing variable. See
[`docs/configuration.md`](docs/configuration.md#mcp-endpoint-authentication) for the
full contract.

> **DCR caveat.** Native discovery leans on your issuer supporting *dynamic client
> registration*. If it doesn't, enable DCR or pre-register the client IDs your MCP
> clients use — otherwise the flow dies mysteriously at registration, which is your
> IdP's concern, not this app's.

This only covers the MCP endpoint. The web UI and REST API (`/api/v1`) have no
in-app authentication of their own — deploy them behind an edge proxy (e.g.
`oauth2-proxy`) or on a network you trust. In-app web
authentication is a planned separate feature; see
[`docs/deployment.md`](docs/deployment.md) for the interim edge-proxy posture and
the cluster changes MCP endpoint authentication requires.

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md)
