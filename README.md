# Platform-up-2-date
## Build for development

Simplest way to build and run the whole application is using Docker. An unfortunate pre-requisite, as of now, is that the backend application has to be build before hand.

Navigate to the backend folder and build using gradle:

```bash
$ cd backend
$ gradle build
```

Then, go back to the root folder and run the docker compose:

```bash
$ cd ..
$ docker compose up -d
```

Verify that the two containers are up and running:

```bash
$ docker ps
```

Then go to localhost:3000 using your favorite browser, et voila!

## API

We're using a contract-first approach between the frontend and backend, where the API is first defined in a json-schema using [API Curio](https://www.apicur.io/), then source files generated for both frontend and backend respectively using [OpenAPI Generator](https://github.com/OpenAPITools/openapi-generator/blob/master/modules/openapi-generator-gradle-plugin/README.adoc). These generated files are to be viewed as immutable source files.

The API Curio studio can easily be hosted locally by running the following command from root:

```bash
$ docker compose -f "compose.apicurio.yml" up -d
```

then [open it up](http://localhost:8888) in your browser. The schemas does not persist, however, so make sure to download them before tearing down the containers.

## Metrics & Alerting

The backend exposes a Prometheus scrape endpoint at `/metrics`, hand-rendered in the
Prometheus text exposition format (no Micrometer). The custom metric for version
monitoring is a single gauge:

```
# HELP platformup2date_version_drift_level How far the deployed version is behind latest (0=current, 1=patch, 2=minor, 3=major)
# TYPE platformup2date_version_drift_level gauge
platformup2date_version_drift_level{app="argo-cd"} 3
platformup2date_version_drift_level{app="git-tea"} 0
```

One gauge answers both questions: whether an app is outdated, and how far behind it is.
The value encodes the highest-significance semver difference between the deployed and
latest version — `0` current, `1` patch behind, `2` minor behind, `3` major behind.
(Pre-release/build-only differences are reported as `1`.)

### Scraping

In the cluster the endpoint is scraped via a Prometheus Operator `ServiceMonitor`
targeting the backend `Service` on its `http` port at path `/metrics`. Both live in
the deployment manifests under `apps/service-spaces/platformup2date/` in the
jumziCluster repo.

### Prometheus alert rules

```yaml
groups:
  - name: platform-up-2-date
    rules:
      - alert: AppOutdated
        expr: platformup2date_version_drift_level > 0
        for: 1h
        labels:
          severity: warning
        annotations:
          summary: "{{ $labels.app }} is behind its latest release"

      - alert: AppMajorVersionBehind
        expr: platformup2date_version_drift_level >= 3
        for: 1h
        labels:
          severity: critical
        annotations:
          summary: "{{ $labels.app }} is a major version behind latest"
```

For more detail than the gauge carries (the actual current/latest version strings),
use the frontend or the `GET /api/v1/version` endpoint.

## MCP endpoint

The backend exposes a [Model Context Protocol](https://modelcontextprotocol.io/) server
so an AI agent can ask "which monitored applications are out of date, and how far behind?"
without any custom integration. It is a third read surface over the same data as `/metrics`
and `GET /api/v1/version` — any MCP-capable host (Claude Desktop, IDE agents, your own)
can connect and discover the tools at runtime.

Transport is **streamable HTTP / SSE**, served at `/mcp/sse` on the backend's HTTP port.

### Tools

- **`list_outdated_applications(minSeverity?)`** — returns the applications running behind
  their latest upstream release, each as `{ name, current, latest, outdated, drift }` where
  `drift` is `PATCH` / `MINOR` / `MAJOR`. The optional `minSeverity` argument
  (`PATCH` | `MINOR` | `MAJOR`) filters by how far behind an app is; omit it to get every
  app with any drift.
- **`get_application(name)`** — returns the status of a single application by exact name,
  or nothing if it isn't monitored.

The drift verdict is computed server-side by the same tested semver logic that backs the
metrics gauge, so the agent never has to compare versions itself.

### Connecting a client

Point any MCP client at the SSE endpoint. For Claude Code:

```bash
claude mcp add --transport sse platformup2date https://platformup2date.example.com/mcp/sse
```

### Security

The endpoint ships **unauthenticated** — it is meant to sit behind a reverse proxy that
enforces access, exactly like the `/metrics` endpoint. Do not expose it directly on an
untrusted network: it enumerates your infrastructure's version drift, which is useful recon
for an attacker.

A common setup is [`oauth2-proxy`](https://oauth2-proxy.github.io/oauth2-proxy/) in front of
`/mcp/sse`, configured to accept bearer JWTs from your OIDC issuer:

```
--skip-jwt-bearer-tokens=true
--oidc-issuer-url=https://auth.example.com/realms/yourrealm
```

Most MCP clients can't perform an interactive OIDC login on their own, so a small wrapper
fetches a token with [`oauth2c`](https://github.com/cloudentity/oauth2c) and bridges the
client's stdio to the protected SSE endpoint via
[`mcp-remote`](https://www.npmjs.com/package/mcp-remote).

**1. Token helper — `oidc-token.sh`** (PKCE public-client flow, cached until the JWT expires):

```bash
#!/usr/bin/env bash
set -euo pipefail

OIDC_ISSUER="${OIDC_ISSUER:-https://auth.example.com/realms/yourrealm}"
OIDC_CLIENT_ID="${OIDC_CLIENT_ID:-platformup2date-mcp}"
CACHE="${XDG_RUNTIME_DIR:-/tmp}/platformup2date-mcp-token"

token_expired() {
  local t="${1:-}"; [ -z "$t" ] && return 0
  local exp
  exp=$(echo "$t" | cut -d. -f2 | tr '_-' '/+' | base64 -d 2>/dev/null | jq -r '.exp // 0')
  [ "$(date +%s)" -ge "$exp" ]
}

token=$(cat "$CACHE" 2>/dev/null || true)
if token_expired "$token"; then
  token=$(oauth2c "$OIDC_ISSUER" \
    --client-id "$OIDC_CLIENT_ID" \
    --response-types code --grant-type authorization_code \
    --auth-method none --pkce --scopes openid --silent \
    | jq -r '.access_token // empty')
  [ -n "$token" ] || { echo "failed to obtain OIDC token" >&2; exit 1; }
  umask 177; printf '%s' "$token" > "$CACHE"
fi
printf '%s' "$token"
```

**2. MCP wrapper — `platformup2date-mcp.sh`** (bridges the client to the protected SSE URL):

```bash
#!/usr/bin/env bash
set -euo pipefail

TOKEN=$("$(dirname "$0")/oidc-token.sh")
MCP_URL="${PLATFORMUP2DATE_MCP_URL:-https://platformup2date.example.com/mcp/sse}"

exec npx -y mcp-remote "$MCP_URL" --header "Authorization: Bearer ${TOKEN}"
```

**3. Register the wrapper with your client:**

```bash
claude mcp add platformup2date -- /path/to/platformup2date-mcp.sh
```

The wrapper runs per session: it reuses the cached token, transparently re-runs the
`oauth2c` login once the JWT expires, and oauth2-proxy validates the bearer token before
the request reaches the backend. The application itself stays auth-agnostic.

> If your client supports remote SSE servers with custom headers directly, you can skip
> `mcp-remote` and pass `Authorization: Bearer $(./oidc-token.sh)` as a header — but a
> wrapper keeps the token fresh across expiries without editing client config.
