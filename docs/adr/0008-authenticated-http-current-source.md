# The HTTP current source can authenticate, with per-app credentials and graceful degradation

The `http` current source ([ADR-0005](0005-version-sources-as-pluggable-factories.md),
[ADR-0007](0007-json-pointer-current-version-extraction.md)) deliberately sent
**no** credentials: its Javadoc warned that registering an auth filter on the
`current` leg — which hits our own deployment endpoints — would be a
secret-exfiltration bug, in contrast to the `latest` leg's GitHub token. Reality
moved: Harbor 2.13+ stops returning `harbor_version` from an *anonymous*
`/api/v2.0/systeminfo`, so the only way to observe its current version is to
authenticate. We therefore let the `http` current source carry credentials —
reversing the old "never on the current leg" stance — while keeping the leak
boundary explicit.

```yaml
current:
  type: http
  url: https://container-registry.sreyardship.com/api/v2.0/systeminfo
  version-key: /harbor_version
  auth:
    type: basic                 # or: bearer
    username: ${HARBOR_USER:}
    password: ${HARBOR_PASS:}
    # token: ${SOME_TOKEN:}     # for type: bearer
```

The design splits along several decisions:

**Per-app, env-expanded credentials.** Unlike `github.token`, which is a single
global token (there is one GitHub), current-leg auth is inherently per-app: each
monitored app has its own URL and its own credentials. So `auth` is a nested
fragment on the per-app `VersionSource` config union, and its *values* are
SmallRye env expansions (`${HARBOR_PASS:}`) — the ConfigMap carries only variable
names; the real secret is a mounted Kubernetes Secret surfaced as env vars. This
mirrors the `github.token` resolution pattern exactly and keeps secrets out of
config.

**Pluggable schemes, one shared bearer filter.** `auth.type` discriminates
`basic` (emits `Authorization: Basic base64(user:pass)`) from `bearer` (emits
`Authorization: Bearer <token>`). The pre-existing `GithubAuthFilter` is
generalised into a shared `BearerAuthFilter` used by both the `github-release`
latest source and the `http` current source; a sibling `BasicAuthFilter` is
added. The secret-exfiltration boundary documentation moves *onto the sources*
(each source owns "to whom do I send credentials"), since the filter classes are
now scheme-generic. As with `GithubAuthFilter`, no host check is performed: the
credential is sent to the app's configured `url`, and a redirect to another host
would replay the header — an accepted residual assumption, because the operator
owns the URL.

**Malformed structure fails the boot; bad values degrade gracefully.** A
*structurally* broken config (an `auth` block with no `type`) fails at startup via
SmallRye's own binding — trying to gracefully recover from malformed config
invites weird half-states. A *value* problem (an unknown `type` string, `basic`
without credentials, or a credential resolving blank) does **not** fail the boot:
the factory logs a clear WARN at startup and returns a `FailedCurrentSource`
carrying the message, so that one app fails every scrape with a precise reason
while the rest of the fleet keeps being monitored — preserving the per-app
isolation principle. Without this, a missing Harbor secret would surface as the
misleading "version-key '/harbor_version' did not resolve" error from
[ADR-0007](0007-json-pointer-current-version-extraction.md).

**Eager build via an Arc-bound collaborator; the lazy build is retired.** To keep
`HttpCurrentSource` a pure POJO — extract a version from a `JsonNode`, nothing
more — the Quarkus REST client (which needs a running Arc context) is built by a
new `@ApplicationScoped` `CurrentVersionClientFactory` collaborator and the ready
client is passed *in*. The factory injects the collaborator and stays POJO-testable
(a fake builder in unit tests); only the thin collaborator is integration-tested.
This replaces the source's former lazy-on-first-`version()` build, whose sole
purpose was to keep the source constructible without Arc — a goal the collaborator
now serves better by separating extraction (POJO) from client construction (Arc).

## Considered Options

- **A single global current-leg credential** (like `github.token`) — rejected:
  there is no single "current" host; every monitored app authenticates against
  its own endpoint, so credentials must be per-app.
- **Generic header injection** (`auth: {header, value}`) — rejected: maximal
  flexibility but pushes scheme correctness onto the operator and is the easiest
  to misconfigure into a leak. `basic`/`bearer` cover the real cases.
- **Literal credentials in config** — rejected: bakes a secret into the
  committed/mounted ConfigMap, the same reason the GitHub token is env-expanded.
- **Fail the boot on any auth misconfiguration** — rejected for *value* problems:
  one missing Harbor secret would blind the operator to every other monitored app
  until fixed. Only genuinely malformed *structure* blocks boot.
- **Keep the lazy per-`version()` build inside the source** — rejected: it forced
  `HttpCurrentSource` to own `QuarkusRestClientBuilder` and so couldn't be a POJO;
  the collaborator gives the same Arc-free constructability with a clean
  extraction/construction split.

## Consequences

- `GithubReleaseLatestSource` keeps its own lazy client build; only the bearer
  *filter* is shared. The inconsistency (latest leg lazy, current leg eager via
  collaborator) is deliberate scope-limiting, not an oversight.
- Eager build means `VersionSourceResolver` triggers real REST-client construction
  for every app during CDI bean construction at boot. If startup ordering ever
  bites, the fallback is a `@PostConstruct`/startup-event build — not a return to
  per-scrape laziness.
- Operationally, Harbor's `/systeminfo` returns `harbor_version` to any
  authenticated principal, so a minimal read-scoped **robot account** suffices —
  no admin — supplied as `HARBOR_USER`/`HARBOR_PASS` via a mounted Secret.
