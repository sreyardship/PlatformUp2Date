# Deploy manifests are kustomize-only; no Helm chart

`docs/deployment.md` described a Kubernetes rollout in prose but shipped no
manifests, so every operator re-derived the same Deployments, Services and
RBAC by hand. We now ship a deployable tree under `deploy/k8s/` — and it is
kustomize-only, deliberately without a Helm chart.

The deciding observation is the shape of this app's variation. Installations
differ by the *presence or absence of whole resources* — a bundled Valkey
yes/no, the `k8s-image` ClusterRole yes/no, an Ingress yes/no, secret mounts
yes/no — plus exactly one user-owned file (`platform-config.yaml`). That is
kustomize's native grammar: components add resources, overlays patch them,
and `configMapGenerator` with `behavior: replace` swaps the config file
wholesale. In Helm the same surface degrades into a `values.yaml` of
`enabled:` flags plus a "paste your platform-config into values" indirection
for a file the app already consumes as a plain mounted ConfigMap
(`QUARKUS_CONFIG_LOCATIONS`). There are almost no scalar knobs to template —
Helm's strength — so a chart would be mostly maintenance surface.

The tree serves both consumption styles with one artifact:

- **Quickstart**: `kubectl apply -k deploy/k8s/overlays/quickstart` — base +
  bundled single-replica Valkey + a host-less, class-less Ingress.
- **GitOps**: reference `deploy/k8s/base` as a remote base pinned by git ref;
  layer an overlay that replaces `platform-config`, creates the well-known
  `platformup2date-env` Secret, and (for HA) points `QUARKUS_REDIS_*` env at
  an existing Sentinel/cluster Valkey. The base bundles no datastore, so
  bring-your-own-Valkey means adding nothing and deleting nothing.

## Considered Options

- **Helm chart (rejected).** Better discoverability (`helm install` is what
  many expect) and built-in release versioning, but the templating buys
  nothing for resource-presence variation, and the platform-config story gets
  worse, not better.
- **Both (rejected).** Maximum reach, double maintenance: every deployment
  change lands twice and both packagings need CI to not rot.
- **Kustomize only (chosen).** One artifact, plain YAML that doubles as
  documentation, native to `kubectl -k` and to Argo CD / Flux.

## Consequences

- Consumers pin by git ref (`…//deploy/k8s/base?ref=vX.Y.Z`); image tags are
  centralised in the base kustomization's `images:` transformer and bumped by
  release CI, so a ref checkout is internally consistent.
- The canonical sample config moved from `docs/samples/platform-config.yaml`
  to `deploy/k8s/base/platform-config.yaml` (kustomize load restrictions bar
  generators from files outside the kustomization root); the compose
  quickstart and docs point there, keeping a single copy.
- "Why is there no Helm chart?" is answered here. If real demand appears, a
  chart can be generated *from* this tree later — the reverse migration is
  the expensive one.
