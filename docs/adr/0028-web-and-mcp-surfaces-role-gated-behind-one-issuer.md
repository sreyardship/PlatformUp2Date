# The web UI and REST API authenticate in-app; every protected surface is role-gated behind one shared issuer

[docs/adr/0026](0026-mcp-endpoint-oauth-resource-server.md) made the MCP surface
an in-app OAuth resource server and explicitly deferred the rest: guarding
`/api/v1` and the web UI was "a separate feature (SPA PKCE login + bearer on
`/api/v1`) with its own design tree." This is that feature. We extend the
resource-server posture to `/api/v1`, make the React SPA an OAuth *client*
(Authorization Code + PKCE), and unify both surfaces under **one shared issuer
with per-surface role gating**. The app still validates tokens and never issues
them or hosts a login — the SPA drives the login against the operator's IdP.

This **amends ADR 0026**. Its two MCP-specific env vars are renamed to a shared
pair, and the MCP surface — previously "any valid token is fully trusted" — now
requires a role like every other surface. Nothing has shipped to users, so the
rename carries no migration cost; the cleaner contract is worth taking now.

## Where authentication lives

The SPA is the OIDC client; the backend stays a **stateless resource server**,
exactly as MCP already is. The browser runs Authorization Code + PKCE, holds the
access token, and presents `Authorization: Bearer` on every `/api/v1` call.

- **Backend-for-frontend (server runs the code flow, sets a session cookie)** —
  rejected. It is more XSS-resistant, but it introduces a second `quarkus-oidc`
  mode (`web-app`) alongside MCP's resource-server mode and cookie-session
  semantics that fight the "every replica serves any surface, holds no state"
  value load-bearing across [0003](0003-scrape-state-centralised-in-valkey.md)
  and [0004](0004-mcp-transport-stateless-for-ha.md).
- **Edge oauth2-proxy as the permanent answer** — rejected. It is the documented
  *interim* posture, cannot mint per-surface identity, and ADR 0026 already
  rejected it for MCP for the same reasons.

## One tenant, per-surface roles

A single shared `quarkus-oidc` tenant: one issuer, one audience naming this app.
**Access control is by role, not audience.** `/api/v1` requires `pu2d-web`;
`/api/mcp` requires `pu2d-mcp`.

Audience answers *"which API is this token for?"* — a property of the resource,
set at issuance by which client asked. Roles answer *"which users may use this
API?"* — a property of the user. The requirement that drove this design — mint
identities that reach the web surface only, the MCP surface only, or both — is a
per-user *authorization* decision, which is what roles/groups are for.

- **Per-surface audiences (app checks `aud` per path)** — rejected as the primary
  control. Audience is not a per-user attribute; "this user gets MCP only" is
  always a group decision underneath (the IdP gates which audience the user can
  mint). Enforcing distinct audiences per path inside one tenant needs
  multi-tenancy or custom per-path audience code. Separate audiences remain
  available as *additive* defense-in-depth (a leaked SPA token cannot even
  validate against MCP), never the access mechanism.
- **Independent per-surface issuers (multi-tenant)** — rejected. In every
  realistic deployment the UI and the MCP tools authenticate against the same
  organizational IdP; a second tenant with a path resolver buys nothing but a
  second JWKS cache and a larger config machine.

## The configuration contract

- **`OIDC_ISSUER` / `OIDC_AUDIENCE`** — shared, both-or-neither. Their presence
  establishes token validation for the whole app. Absent = today's fully open
  behavior, unchanged.
- **`WEB_OIDC_ROLE` / `MCP_OIDC_ROLE`** — the presence of each *is* the
  per-surface switch, and its value is the required role string (default
  `pu2d-web` / `pu2d-mcp`). Set = that surface demands that role; unset = that
  surface stays open. This is how "web on, MCP off" (or the reverse) is expressed
  now that the issuer is shared and can no longer toggle a single surface.
- **Half-states stay unrepresentable.** A role variable set with no `OIDC_ISSUER`
  is a boot failure — a role to enforce with nothing to validate against. No
  `_ENABLED` booleans: they would reintroduce exactly the enabled-but-no-issuer
  state ADR 0026 rejected the boolean to avoid.
- **Role names are operator-chosen** so they match an existing realm taxonomy and
  do not collide with a generic role in a realm shared with other apps; the
  namespaced defaults keep the zero-config case safe.
- **Claim source is Quarkus's default role extraction** (Keycloak realm/client
  roles, or a top-level `groups`/`roles` claim), overridable via
  `quarkus.oidc.roles.role-claim-path`. As in ADR 0026, the two `OIDC_*` env vars
  plus the two role vars are the whole operator contract; the underlying
  `quarkus.oidc.*` properties are deliberately undocumented as contract.
- **A valid token carrying the surface's role is fully trusted** — no finer
  scopes gating individual endpoints or tools. Same all-or-nothing spirit as ADR
  0026, now applied per surface; finer scopes stay an additive future change.

## The SPA as OIDC client

- **Runtime-configured, like `API_BASE_URL`.** The SPA reads
  `window._env_.OIDC_AUTHORITY` + `OIDC_CLIENT_ID` at runtime. Present → wire the
  OIDC provider and require login before `/api/v1`; absent → behave exactly as
  today (no login, no bearer). The whole stack agrees on off-by-default, injected
  per deployment rather than baked at build time.
- **Authorization Code + PKCE, full-page redirect**, via `oidc-client-ts` /
  `react-oidc-context` (the maintained standard — gets PKCE/state/nonce and
  renewal right).
- **Access token in memory only** — never `localStorage`/`sessionStorage`, which
  any XSS can read. **Silent renew** keeps the session alive across reloads.
- **RP-initiated logout** — clearing the local token *and* ending the IdP session,
  the honest meaning of "log out."
- **Interceptor consequences** (`frontend/src/api/axiosClient.js`): attach the
  bearer only when auth is enabled; **401** (missing/expired/invalid) → silent
  renew, then redirect; **403** (valid token, missing `pu2d-web`) → the new *Not
  authorized* state — distinct from *Backend unavailable*, because the backend
  answered, and answered no.

## Considered Options

- **Topology**: (chosen) SPA OIDC client + stateless resource server; BFF cookie
  session; permanent edge oauth2-proxy.
- **Access mechanism**: (chosen) per-surface roles with one shared audience;
  per-surface audiences; OAuth scopes.
- **Config shape**: (chosen) shared issuer/audience + role-presence switches;
  per-surface `*_OIDC_ISSUER` vars that must agree; explicit `_ENABLED` booleans.

## Consequences

- **The SPA static shell is always publicly served.** Enabling web auth gates
  `/api/v1`, not the page — the browser must load the app to run the login flow.
  An anonymous visitor loads the UI and is redirected to log in; the page itself
  is never hidden. A BFF or edge proxy *would* hide it; topology A structurally
  cannot, and the docs must say so.
- **No ingress change for web auth.** Unlike ADR 0026's
  `/.well-known/oauth-protected-resource` HTTPRoute rule, the SPA discovers the
  IdP directly, so nothing new is served at the host root for the web surface.
  (ADR 0026's MCP well-known rule still stands for MCP.)
- **Dev-mode CORS must allow the `Authorization` header** for
  `localhost:3000 → :8080`; production is same-origin behind the `/api` route, so
  no CORS there.
- **ADR 0026 is amended**: `MCP_OIDC_ISSUER`/`MCP_OIDC_AUDIENCE` →
  `OIDC_ISSUER`/`OIDC_AUDIENCE`, and the MCP surface now requires `pu2d-mcp`. The
  README/deploy docs for MCP auth change accordingly.
- **Tests** mirror ADR 0026 and add the role dimension: a JVM auth-on IT against
  Keycloak Dev Services (with-role 200, no-role 403, no-token 401, wrong-audience
  401); the **cross-surface isolation test** (a `pu2d-web`-only token gets 403 on
  `/api/mcp`, and the reverse) that proves only-web/only-mcp/both actually works;
  a boot-failure test on role-without-issuer; an open-surface regression on
  `/metrics` + `/q/health`; and the auth-off IT kept as the default-mode guard.
  The frontend covers enabled/disabled wiring and the 401/403 interceptor paths.
  Native-image OIDC reachability (ADR 0025) leans on MCP's existing auth-on native
  smoke — role extraction is ordinary claim-reading — with no separate native web
  smoke unless a reachability bug surfaces.
