# The oci-registry latest source authenticates by obeying the registry's own bearer challenge

The `oci-registry` latest source reads an app's latest version from the published
image tags of any OCI-spec registry (`GET /v2/<repo>/tags/list`). The endpoint is
uniform across registries, but **authentication is not**: Docker Hub, GHCR and
Quay require a bearer-token *dance* (the first request returns `401` with a
`WWW-Authenticate: Bearer realm=…,service=…,scope=…` header; the client mints a
short-lived token from that `realm` and retries), and Docker Hub requires it
**even for anonymous pulls of public images**, whereas Harbor and a plain
`registry:2` accept HTTP Basic directly. To stay genuinely registry-agnostic, the
source performs the full dance and **echoes the challenge verbatim** — it replays
the `realm`, `service` and `scope` the registry advertised rather than
constructing them — so each registry self-describes its own token flow and the
source never branches on *which* registry it is talking to. Configured `basic`
credentials (when present) are sent to the `realm` token endpoint; absent, the
token is minted anonymously.

## Considered Options

- **Static credentials only** (reuse the existing `auth` fragment, send `basic`/
  `bearer` straight at `/v2/...`) — rejected: works for Harbor/`registry:2` but
  **fails Docker Hub, GHCR and Quay entirely**, including public images, which
  won't honour a static header on `tags/list` without the challenge. Docker Hub is
  the single most likely target for a "general registry" feature, so excluding it
  guts the value.
- **Construct `realm`/`scope` ourselves** (hardcode the token URL and
  `scope=repository:<repo>:pull` per registry) — rejected: this bakes in exactly
  the per-registry coupling the feature exists to avoid. We keep a constructed
  `scope` only as a fallback for a registry that omits `scope` from the challenge
  (spec-permitted, rare).
- **Cache the minted token** — rejected: registry tokens are short-lived
  (~5 min) and `oci-registry` sources are built once at startup and reused across
  scrapes that run hourly + budgeted, so a cached token is almost always expired by
  the next scrape. The source mints a fresh token on every `version()` call and
  stays stateless, matching how `github-release` re-reads on every call.

## Consequences

- Each scrape of an `oci-registry` app is 2–3 HTTP calls (`tags/list` → `401` →
  mint → `tags/list` with bearer). Negligible at the scrape cadence.
- The existing static-token `BearerAuthFilter` does **not** suffice; the dance
  needs a mechanism that reads the `401` challenge, mints from the advertised
  `realm`, and retries. The configured credential is `basic` only — `bearer`/
  `token-file` are refused by the factory (they send a static token at the
  resource and don't fit the mint flow), surfacing as a `FailedLatestSource`.
- **Exfiltration boundary:** configured `basic` credentials are sent to the
  registry's advertised `realm` host, which is discovered at runtime from the
  challenge rather than pinned in config. The assumption that the `realm` a given
  registry returns is trustworthy lives in configuration (the operator chose the
  `registry` host), not in a host check — consistent with the residual assumption
  recorded for the GitHub token (ADR-0011) and the `http` current source
  (ADR-0008).
