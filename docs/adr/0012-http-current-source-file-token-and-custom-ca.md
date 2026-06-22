# The HTTP current source can read its bearer token from a rotating file and pin a per-scraper CA

The authenticated `http` current source ([ADR-0008](0008-authenticated-http-current-source.md))
sourced its bearer token from an env-expanded `auth.token` read once at boot, and trusted
endpoints via the JVM default truststore. That serves Harbor but not a private endpoint behind a
private CA whose credential rotates — the motivating case being the Kubernetes API, reproducing:

```bash
curl --cacert /var/run/secrets/kubernetes.io/serviceaccount/ca.crt \
     -H "Authorization: Bearer $(cat /var/run/secrets/kubernetes.io/serviceaccount/token)" \
     https://kubernetes.default.svc/version
```

Rather than a fabric8/k8s-specific source, we extend the generic `http` current source with two
**independent** capabilities — a file-sourced bearer token and a pinned CA — so any private service
behind a private cert with a file-mounted token is reachable, not just Kubernetes. This reverses
nothing in ADR-0008; it adds to it.

```yaml
current:
  type: http
  url: https://kubernetes.default.svc/version
  ca-cert: /var/run/secrets/kubernetes.io/serviceaccount/ca.crt   # TLS trust, sibling of url
  auth:
    type: bearer
    token-file: /var/run/secrets/kubernetes.io/serviceaccount/token   # alternative to `token`
```

The design splits along several decisions:

**Two orthogonal axes, two config homes.** `ca-cert` is a *transport* concern (whom do I trust at
the TLS layer) and lives as a sibling of `url` on the per-app `VersionSource`, usable with or
without any `auth` block. `token-file` is a *credential* concern and lives inside the existing
`auth` fragment under `type: bearer`, alongside `token`. The curl command bundles the two, but they
are independent: a private CA with no auth, or a file token over a publicly-trusted cert, are both
valid.

**The token file is re-read per request; the CA is read once at boot.** This asymmetry is
deliberate and reflects what each layer can actually do. A projected Kubernetes serviceaccount token
*rotates* on disk (refreshed well before expiry), so reading it once at boot would hand out a string
that expires into a 401 storm until the pod restarts — defeating the very case the feature exists
for. So `token-file` is read (and trimmed) inside a new `FileBearerAuthFilter` on every request; the
static `token` keeps the original `BearerAuthFilter`, which closes over a fixed string. The CA, by
contrast, is effectively static — a CA roll is a restart-worthy event — and the Quarkus REST
client's truststore is fixed at *build* time, so `ca-cert` is read once when the client is
constructed at boot. Picking up a rotated CA without a restart is explicitly out of scope.

**Replace, scoped to one scraper.** When `ca-cert` is set, the truststore contains *only* the
supplied CA (matching `curl --cacert`, not augmenting the JVM default bundle): each per-app client
hits exactly one private endpoint and should trust exactly the CA that signs it, nothing else.
Critically, "replace" means replace *that one scraper's* truststore — it is set on the per-app
`QuarkusRestClientBuilder` for that single `HttpCurrentVersionClient`, never via a JVM-global
`javax.net.ssl.trustStore` or a shared TLS-registry default. App A pinning the cluster CA does not
change what app B trusts; an app with no `ca-cert` keeps the JVM default bundle. A future reader
must not "simplify" this into a global truststore.

**Exactly one bearer credential; both-set is refused, not ranked.** Under `type: bearer`, exactly
one of `token` / `token-file` must be present. Neither is the pre-existing "missing token" value
error. Both present is rejected outright rather than given a precedence rule, because a silent
precedence is a footgun: the operator would not know whether the literal or the file is live. Both
the neither and both cases degrade to `FailedCurrentSource` (a logged WARN, not a boot crash),
preserving ADR-0008's per-app isolation.

**Boot validates structure and paths; value/IO problems degrade one app.** Following ADR-0008,
boot-time validation checks only that a configured `token-file` path is non-blank and that a
configured `ca-cert` parses into at least one X.509 certificate; structurally broken config (an
`auth` block with no `type`) still fails SmallRye binding at boot. Everything else degrades
gracefully: a missing/unreadable/blank `token-file` *at request time* fails that one app's scrape
with a precise reason while the fleet keeps being monitored; a missing/unreadable/non-PEM/zero-cert
`ca-cert` *at boot* returns a `FailedCurrentSource` rather than crashing the boot — so a fat-fingered
cert path blinds one app, not the whole fleet.

**The factory reads the cert; the client factory only wires it.** Building a `KeyStore` from a PEM
is pure JDK (`CertificateFactory` + `KeyStore`), so it stays in `HttpCurrentSourceFactory` next to
the existing auth-value validation: that factory reads `ca-cert`, builds the truststore, and maps
any failure to `FailedCurrentSource` — the one place that decision already lives. The Arc-bound
`HttpCurrentVersionClientFactory.build(...)` grows an `Optional<KeyStore>` parameter and merely calls
`builder.trustStore(...)`, staying the thin "register stuff on the builder" boundary it already is
for the auth filter and the exception mapper.

## Considered Options

- **A k8s-specific source via fabric8** — rejected: ties the feature to Kubernetes when a generic
  file-token + custom-CA `http` source serves any private service behind a private cert with a
  file-mounted bearer token.
- **Read `token-file` once at boot** (reuse `BearerAuthFilter`) — rejected: simpler, but silently
  breaks after the first serviceaccount-token rotation, so the one case the feature exists for is
  the one it fails. Per-request re-read of a small local file is negligible cost.
- **Augment the default truststore with the supplied CA** — rejected: broader trust than a
  single-endpoint client needs, and quietly defeats the point of pinning. Replace mirrors `curl
  --cacert`.
- **A precedence rule when both `token` and `token-file` are set** — rejected: a silent footgun;
  refuse and make the operator choose.
- **Fail the boot on a bad `ca-cert` or missing token mount** — rejected for value/IO problems: one
  bad cert path or a briefly-absent token mount during a rollout would blind the operator to every
  other monitored app. Only genuinely malformed config *structure* blocks boot.
- **`ca-cert` nested under `auth`** — rejected: bundles transport trust with credentials, but TLS
  trust is independent of (and usable without) auth.

## Consequences

- The read-timing asymmetry is intentional: token re-read per request (filter-level, rotates), CA
  read once at boot (build-level, static). A `ca-cert` valid at boot but later deleted does not
  re-fail until restart — consistent with the build-time truststore.
- `GithubReleaseLatestSource` (the `latest` leg) is untouched: it hits `api.github.com` with a
  global token over the public CA and has no private-CA or file-token case. A future self-hosted
  releases endpoint behind a private cert would need the same treatment extended to that source.
- No glossary change: these are credential/transport mechanics; *version source* (CONTEXT.md)
  already covers the concept and no new domain term is introduced.
