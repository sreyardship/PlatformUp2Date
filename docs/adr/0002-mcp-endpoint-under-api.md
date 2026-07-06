# The MCP endpoint lives at /api/mcp, a sibling of /api/v1 — not a child

The MCP transport is served at `/api/mcp/sse` (via
`quarkus.mcp.server.http.root-path: api/mcp`), relocated from the extension's
default `/mcp`. It sits *beside* the versioned REST API, not underneath it: the
path is `/api/mcp`, deliberately **not** `/api/v1/mcp`.

Two forces drive this. First, the cluster `HTTPRoute` routes by prefix —
`/api` to the backend (unstripped), `/` to the frontend — so anything the
backend must own has to live under `/api`. At the default `/mcp` the endpoint
falls through to the catch-all `/` rule and is handed to the *frontend*, which
does not serve it; the endpoint is unreachable through the public host until it
moves under `/api`. Second, the `v1` in `/api/v1` versions the OpenAPI/REST
*contract* (the JSON shape the frontend and `GET /version` consumers depend on).
MCP is a separate protocol that negotiates its own `protocolVersion` and evolves
its surface through tool definitions, independently of that contract. Nesting it
under `v1` would falsely couple the two — implying a REST contract bump to `v2`
drags the MCP path along.

## Considered Options

- **Default `/mcp`** — rejected: not under the `/api` prefix, so the existing
  HTTPRoute sends it to the frontend and it is unreachable. Would force a
  bespoke ingress rule per environment, the opposite of the routing goal.
- **`/api/v1/mcp`** (mirror the REST versioning) — rejected: it ties the MCP
  path to the REST contract's version, which is misleading because the two
  version independently. A future `/api/v2` REST contract should not move or
  duplicate the MCP endpoint.
- **`/api/mcp`** (chosen) — under the shared `/api` prefix for routing, but a
  sibling of `v1`, reflecting that MCP carries its own protocol versioning.

## Consequences

- No cluster/ingress change is needed to expose MCP: the existing `/api`
  PathPrefix rule absorbs `/api/mcp/sse`, and edge oauth2-proxy already protects
  the whole host, so MCP inherits the same auth as the REST API and `/metrics`.
- `McpAssured` does not read `quarkus.mcp.server.http.root-path`; its test client
  defaults to `/mcp/sse`. Integration tests must set the path explicitly
  (`setSsePath("/api/mcp/sse")`), which doubles as the regression guard if config
  and tests ever drift apart.
- The public URL consumers register changes to `…/api/mcp/sse`. Any MCP client
  already registered against `…/mcp/sse` must re-register with the new path.
  The README and OIDC wrapper scripts are updated to the new path.
- Should the REST contract ever go `/api/v2`, the MCP endpoint stays put at
  `/api/mcp`; it is not republished alongside the new contract version.
