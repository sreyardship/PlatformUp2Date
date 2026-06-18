# Current and latest versions come from pluggable version sources, not a single repository

Until now an Application's *current* version was always an HTTP `/version`
endpoint and its *latest* always a GitHub release, both URLs hard-wired as bare
strings in `platform-config` and both fetched by one `VersionRepository.scrape()`
adapter (`ApplicationVersionClient`). Many platform apps (operators, controllers,
anything behind no HTTP surface) expose no version endpoint, but their running
version is plainly the deployed container image tag in the cluster — which is
also a *truer* observation of what's running than a self-reported endpoint (see
[docs/adr/0001](0001-applications-are-upstream-components.md), "observed running
version").

We therefore make a version's origin **polymorphic**. Both `current` and
`latest` become tagged unions in config (`type:` discriminator):

```yaml
current:
  type: k8s-image          # or: http
  namespace: argocd
  workload: deployment/argocd-server
  container: argocd-server
latest:
  type: github-release     # helm-index anticipated by ADR-0001
  url: https://api.github.com/repos/argoproj/argo-cd/releases/latest
```

The architecture splits along two decisions:

**Two core out-ports, scrape orchestration in the core.** `VersionRepository`
is replaced by `CurrentVersionSource` and `LatestVersionSource`, each a pure
capability (`Version read()`) with zero config knowledge. The scrape loop — for
each app read both, isolate per-app failures, count attempted/failed — moves out
of the adapter and into `ApplicationVersionService` where it belongs: it is
application policy, not a transport detail. The core never imports Fabric8 or the
REST client and never learns a `type` string.

**One discovered factory per source kind.** Per-app sources are plain non-CDI
objects (`new HttpCurrentSource(url)`), each owning its own construction. The
discoverable unit is a stateless factory per kind (`type()` + `create(cfg)`),
registered by mere existence as a CDI bean. A `VersionSourceResolver` indexes
`Instance<…Factory>` into a `Map<type, factory>` (failing fast on duplicate or
unknown type) and calls `create(cfg)` once per app. Adding a source kind is a new
factory class and nothing else — open-closed holds. Each factory validates its
own config fragment (k8s-image requires namespace/workload/container; http
requires url).

The k8s-image source reads the image tag off the **workload's pod template**
(`spec.template.spec.containers[].image`), not from live pods — the template is
the single declared desired version and avoids mid-rollout ambiguity. It requires
an explicit `kind/name` and `container`, and parses the tag through the existing
`Version` primitive.

## Considered Options

- **A single switch in the resolver instead of factories** — `case "http" ->
  new HttpCurrentSource(...)`. Less machinery, and viable since there are only
  ~2 kinds per side. Rejected because we want auto-registration as a standing
  property: a new kind should not require editing a central dispatcher. The
  factory a discovery mechanism forces into existence is near-empty, but it is
  the deliberate price of open-closed.
- **`ServiceLoader` / hand-rolled classpath scan instead of CDI** — rejected:
  both still require a config-free no-arg producer (a factory by another name),
  and in Quarkus CDI discovery *is* the build-time classpath scan, so anything
  else is more boilerplate for the same result.
- **Source kinds as first-class core ports, dispatch in core** — rejected: the
  `type` discriminator, factories and resolver are driven-side composition
  concerns; surfacing them in core would leak substrate knowledge and break the
  dependency rule.
- **Reading the running pod's image rather than the template** — rejected:
  during a rollout old and new pods coexist, giving an ambiguous answer; the
  template is the declared desired version.

## Consequences

- The backend gains a Kubernetes API dependency (Fabric8 via
  `quarkus-kubernetes-client`), an in-cluster ServiceAccount, and a cluster-wide
  read-only ClusterRole (`get` on deployments/statefulsets/daemonsets). RBAC
  lives in the GitOps repo so that adding a monitored app in a new namespace
  stays a ConfigMap edit, not a redeploy — consistent with ADR-0001's
  ConfigMap-driven app set.
- Per-app sources are `Closeable` (REST clients, the k8s client). Lifecycle
  ownership — closing them on shutdown — moves from `ApplicationVersionClient`'s
  `@PreDestroy` to whatever holds the assembled sources, and must stay explicit.
- Config errors for a source surface at startup *from its factory*, not from
  config mapping, because SmallRye `@ConfigMapping` does not model discriminated
  unions natively.
- A k8s-image whose tag is a digest or a non-semver tag (`latest`, `stable`)
  fails that app's scrape and is counted in `failed` — the same per-app
  isolation that already applies to unreachable HTTP endpoints.
