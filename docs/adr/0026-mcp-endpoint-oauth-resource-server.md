# The MCP endpoint authenticates its own callers as an OAuth resource server, configurable and off by default

MCP clients could not authenticate against the edge oauth2-proxy without the
wrapper-script hack (`oidc-token.sh` + `mcp-remote`), because the MCP
authorization spec expects the *server itself* to act as an OAuth 2.1 resource
server: challenge with `WWW-Authenticate`, publish RFC 9728 protected-resource
metadata at `/.well-known/oauth-protected-resource…`, and validate bearer JWTs
per request. We adopt exactly that, via `quarkus-oidc` — the app validates
tokens and advertises its issuer; it never issues tokens or hosts a login. This
amends [docs/adr/0002](0002-mcp-endpoint-under-api.md)'s posture that the app
stays auth-agnostic behind edge auth: for the MCP surface, authentication is now
the app's job.

Per-request bearer validation is the only shape compatible with the stateless
transport of [docs/adr/0004](0004-mcp-transport-stateless-for-ha.md) — there is
no session to bind a login to.

## The configuration contract

- **Presence-based switch, off by default.** Setting `MCP_OIDC_ISSUER` turns
  enforcement on; unset preserves today's open endpoint. This matches the
  existing config idiom (`GITHUB_TOKEN` absent = unauthenticated) and makes the
  half-configured states unrepresentable. The startup log states which mode the
  endpoint booted in, so a typo'd variable name is visible on first boot.
- **`MCP_OIDC_AUDIENCE` is mandatory when the issuer is set** — boot failure if
  missing. Accepting any-audience tokens would let a token minted for another
  service in the same realm replay against this one.
- These two env vars are the whole operator contract; advanced needs reach the
  underlying `quarkus.oidc.*` properties, which are deliberately undocumented
  as contract.
- **A valid token is fully trusted** — no scopes/roles gating individual tools.
  The scrape budget (docs/adr/0006) already bounds what any caller can burn,
  and edge auth had the same all-or-nothing semantics; scopes are an additive
  change if a real read-only-agent need appears.

## Considered Options

- **Keep edge oauth2-proxy as the only MCP auth** — rejected: MCP clients
  cannot drive an interactive OIDC login through a proxy; the wrapper scripts
  it forces are the problem this decision removes.
- **Guard the whole backend (`/api/v1`, `/metrics`) with the same in-app
  OAuth** — rejected for now: the REST API's consumer is our own SPA frontend,
  which has no token machinery; in-app web auth is a separate feature (SPA
  PKCE login + bearer on `/api/v1`) with its own design tree. Until it lands,
  the UI and REST API are protected by an edge proxy or a private network —
  never by this switch, and the docs must say so plainly.
- **Explicit `MCP_AUTH_ENABLED` boolean** — rejected: introduces the
  enabled-but-no-issuer and issuer-but-disabled states for no benefit.
- **In-app OAuth resource server on `/api/mcp` only, presence-switched
  (chosen).**

## Consequences

- **ADR 0002's "no ingress change needed" no longer holds when auth is on.**
  RFC 9728 metadata lives at the host root (`/.well-known/oauth-protected-
  resource/api/mcp`), outside the `/api` prefix — the cluster HTTPRoute would
  hand it to the frontend. Enabling MCP auth behind the shared route requires
  one added PathPrefix rule (`/.well-known/oauth-protected-resource` →
  backend, unstripped). We do not relocate the metadata under `/api`; fighting
  the well-known convention breaks client-side URL derivation.
- **Any edge proxy still in front of the host must get out of MCP's way**:
  bypass rules (e.g. oauth2-proxy `skip-auth-route`) for `/api/mcp` and
  `/.well-known/oauth-protected-resource*`, or the proxy intercepts the
  challenge/discovery requests before the app can answer and native client
  auth breaks.
- The README's MCP section drops the oauth2-proxy + wrapper-script setup
  entirely; the native flow is the documented path. Edge-proxy material moves
  to the deployment doc as the interim UI/REST answer.
- Native client discovery leans on the *authorization server* supporting
  dynamic client registration (or pre-registered client IDs); the docs must
  say "enable DCR on your issuer or pre-register your MCP clients", or the
  first user's flow dies mysteriously at registration. Operator's IdP concern,
  not app scope.
- Auth-on integration tests run against Keycloak Dev Services with a dedicated
  `@TestProfile` (real issuer discovery, JWKS, audience check — mock
  identities would bypass exactly the machinery being added); the existing
  auth-off IT stays untouched as the default-mode regression guard. The
  wrong-audience rejection test is the guard on the mandatory-audience rule.
  The native image gets a build + smoke of the auth-on path (this project has
  prior reachability scars, docs/adr/0025).
