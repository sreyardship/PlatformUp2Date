# The MCP transport is stateless (Streamable HTTP + auto-init), not session-bound

Running more than one replica broke the MCP endpoint: the SSE transport
(`quarkus-mcp-server-sse`) is session-stateful — a client opens a long-lived
stream on one replica and POSTs follow-up messages to a session bound to *that*
replica — so under the cluster's round-robin mesh the follow-up POSTs land on a
different replica and fail with "session not found". The session cannot be
externalised to Valkey to fix this: an MCP connection owns a live Vert.x
`HttpServerResponse` socket, which is not serialisable and cannot move between
pods (the plugin's proposed `ConnectionManager`/`ConnectionStore` SPI was
rejected and closed unimplemented for exactly this reason), and stream
resumability/redelivery is not implemented either.

We therefore **migrate off SSE to the Streamable HTTP transport and run it
stateless** via `quarkus.mcp.server.http.streamable.auto-init: true`, which
auto-initialises a fresh, throwaway MCP session for every request. Any replica
serves any request, pod restarts and rollouts are invisible to clients, and **no
session affinity / sticky-session configuration is needed in the mesh.** This is
the natural completion of [docs/adr/0003](0003-scrape-state-centralised-in-valkey.md):
all scrape state already lives in Valkey and every tool is a pure function over
it, so a throwaway per-request session loses nothing.

**Scope boundary.** This choice forecloses every *server-initiated* MCP feature —
sampling, elicitation, roots, and live `notifications/*` (including streaming
progress) — because those require a durable session and a live stream that
statelessness deliberately discards. We accept that boundary: long-running work
such as a manual scrape is exposed as **pollable state read through a tool**
(written to Valkey by whichever replica scrapes), never as a server push. A
future MCP server that genuinely needs server-initiated interaction is an
explicit exception that must run stateful (session affinity, non-graceful
restart) and justify itself in its own ADR. This is the intended reference
posture for MCP servers on the platform.

## Considered Options

- **Keep SSE + sticky sessions** — rejected: SSE has *no* stateless mode, so it
  forces session affinity (mesh consistent-hash on the session). Affinity is
  fragile across rollouts/pod restarts (the session is lost and the stream drops
  with no resumable recovery), pins clients to possibly-unhealthy replicas, and
  the transport is deprecated as of MCP 2025-03-26.
- **Streamable HTTP, stateful + session affinity** — rejected: same restart
  fragility as above, plus it fights this app's model — a manual scrape is
  coordinated by a cluster-wide Valkey lock, so the replica holding a client's
  session is usually *not* the one running the scrape, meaning even native
  progress would need cross-pod fan-out on top of affinity.
- **Externalise the session to Valkey** — not possible: the live stream socket
  is not serialisable; the plugin SPI for this was closed unimplemented.
- **Single replica for the MCP surface** — rejected: discards the HA this work
  exists to support.
- **Streamable HTTP, stateless via `auto-init` (chosen)** — seamless HA with no
  mesh changes, consistent with ADR 0003, at the cost of server-initiated
  features the monitoring tools do not use.

## Consequences

- **The public MCP URL changes** from `…/api/mcp/sse` to `…/api/mcp` (Streamable
  HTTP has a single endpoint, no `/sse` suffix). This supersedes the
  SSE-specific consequences noted in [docs/adr/0002](0002-mcp-endpoint-under-api.md);
  the `/api/mcp` path location and its routing rationale are unchanged. Clients
  re-register with `--transport http` against the new URL; the README and OIDC
  wrapper scripts are updated.
- **No mesh/Istio change is required** — there is nothing to make sticky. The
  existing `/api` HTTPRoute and edge auth continue to cover `/api/mcp`.
- **Each request pays the `initialize` handshake** (a throwaway session). At
  monitoring call rates this is negligible relative to the Valkey read each tool
  performs.
- **Integration tests move from the SSE test client to the Streamable HTTP test
  client** (`McpAssured`), targeting `/api/mcp` instead of `setSsePath`.
- **Server-initiated MCP features are off the table** for this server by
  construction; any need for them reopens this decision.
