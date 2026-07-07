# Deploying to Kubernetes

The README's quick start (`compose.quickstart.yml`) is for trying the tool out
locally. This page is for running it for real, on your own cluster.

Kubernetes is the primary deployment target — it's where the tool is developed
and run — but nothing requires it: both images are plain OCI images that run on
any OCI-compatible runtime (Docker, Podman, containerd), as the compose
quickstart shows. The one Kubernetes-only piece is the `k8s-image` current
source, which reads workloads from the Kubernetes API and auto-configures
in-cluster.

The backend is designed to run highly available. All scrape state lives in
Valkey and every surface — REST, frontend, metrics, MCP — is a stateless
projection of it, so replicas are interchangeable: run as many as you like
with no session affinity, and a scrape on any replica updates what all of
them serve (see [`docs/adr/0003`](adr/0003-scrape-state-centralised-in-valkey.md)
and [`docs/adr/0004`](adr/0004-mcp-transport-stateless-for-ha.md)).

## The shipped manifests

Deployable manifests live under [`deploy/k8s/`](../deploy/k8s/) as a kustomize
tree (kustomize-only on purpose — see
[`docs/adr/0027`](adr/0027-deploy-manifests-are-kustomize-only.md)):

```
deploy/k8s/
├── base/                  # backend (2 replicas) + frontend, namespace-agnostic
│   └── platform-config.yaml   # the canonical sample config (a ConfigMap you replace)
├── components/
│   ├── valkey/            # bundled single-replica Valkey (quickstart datastore)
│   └── rbac/              # opt-in ClusterRole for the k8s-image source
└── overlays/
    └── quickstart/        # namespace + base + valkey + a host-less Ingress
```

### Try it on a cluster

```bash
kubectl apply -k deploy/k8s/overlays/quickstart
```

This creates the `platformup2date` namespace and serves the UI through a
host-less, class-less Ingress on your cluster's default IngressClass (k3s and
minikube ship one; elsewhere, patch `ingressClassName` — the patch is shown in
[`overlays/quickstart/ingress.yaml`](../deploy/k8s/overlays/quickstart/ingress.yaml)).
The routing contract is: `/api` → backend **unstripped**, everything else →
frontend; the frontend's `API_BASE_URL` stays empty so browser calls are
same-origin (no CORS).

### Run it for real: write an overlay

Reference the base as a remote base, pinned to a release tag so the image pins
inside it match:

```yaml
# kustomization.yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
namespace: my-namespace

resources:
  - https://github.com/sreyardship/PlatformUp2Date-public//deploy/k8s/base?ref=v0.0.48
  - namespace.yaml

# Your real monitoring config, replacing the sample wholesale. The generated
# hash suffix rolls the backend automatically on every config change.
configMapGenerator:
  - name: platform-config
    behavior: replace
    files:
      - platform-config.yaml
```

Argo CD and Flux both consume this directly (it's a plain kustomization).

- **Credentials** (`GITHUB_TOKEN`, `HARBOR_USER`/`HARBOR_PASS`, …) need no
  patching at all: the backend Deployment carries an `optional: true`
  `envFrom` hook on a Secret named `platformup2date-env` — create it and its
  keys become env vars:

  ```bash
  kubectl -n my-namespace create secret generic platformup2date-env \
    --from-literal=GITHUB_TOKEN=ghp_…
  ```

  File-shaped secrets (the `ssh-os-release` private key, custom CAs) stay
  volume mounts — add the Secret volume and `volumeMounts` patch in your
  overlay (see [`configuration.md`](configuration.md#type-ssh-os-release-current--tier-b-requires-ssh-access)
  for the paths the config expects).
- **Ingress**: the base ships none (the quickstart's Ingress lives in the
  overlay). Add your own Ingress/HTTPRoute honouring the routing contract
  above.

### Valkey: bundled or bring your own

The base deliberately bundles **no** datastore. The quickstart adds
`components/valkey`: a single replica, no persistence — honest for this
workload, since scrape state is rebuildable (losing the pod costs one
re-scrape; only the fleet staleness clock and manual-scrape budgets reset).

For an HA datastore, run your own Valkey/Redis (operator, managed service,
Sentinel — anything Redis-API-compatible) and point the backend at it with an
env patch; the storage adapter uses only simple single-key commands, so
standalone, Sentinel and cluster topologies all work:

```yaml
patches:
  - patch: |-
      apiVersion: apps/v1
      kind: Deployment
      metadata:
        name: platformup2date-backend
      spec:
        template:
          spec:
            containers:
              - name: backend
                env:
                  - name: QUARKUS_REDIS_HOSTS
                    value: redis://my-valkey:6379
```

For Sentinel, set `QUARKUS_REDIS_CLIENT_TYPE=sentinel`,
`QUARKUS_REDIS_HOSTS=redis://sentinel-0:26379,redis://sentinel-1:26379,…` and
`QUARKUS_REDIS_MASTER_NAME=<master>` instead. One caveat worth knowing: with
async replication a failover can lose the last few writes — here that means at
worst one re-scrape, which is exactly why the state model is HA-friendly.

While Valkey is unreachable the backend fails closed (503) and its readiness
probe (`/q/health/ready`) reports DOWN, pulling replicas out of rotation until
it returns.

### k8s-image RBAC

Only if an app in your config uses the `k8s-image` current source, add
`components/rbac` to your overlay:

```yaml
components:
  - https://github.com/sreyardship/PlatformUp2Date-public//deploy/k8s/components/rbac?ref=v0.0.48
```

It grants cluster-wide read-only `get` on Deployments/StatefulSets/DaemonSets —
no `list`/`watch`, no writes, no secrets — to the `platformup2date`
ServiceAccount the base always creates. **Namespace caveat**: a
ClusterRoleBinding subject names a concrete namespace and kustomize's
`namespace:` transformer does *not* rewrite it (it only rewrites subjects named
`default`). Deploying anywhere other than a namespace literally called
`platformup2date`, patch it:

```yaml
patches:
  - patch: |-
      - op: replace
        path: /subjects/0/namespace
        value: my-namespace
    target: {kind: ClusterRoleBinding, name: platformup2date-read-workloads}
```

## What the backend needs (outside Kubernetes)

Running on plain Docker/Podman/anything-OCI instead? The base manifests wire
up exactly two things you'll need to reproduce (the compose quickstart shows
both):

- **A reachable Valkey (or Redis-API-compatible) instance.** All scrape state —
  the observed Applications, the fleet-wide staleness clock, and the manual-scrape
  budgets — lives centrally in Valkey, not in the JVM. This makes every
  replica serve the same snapshot, but it is a hard dependency: if Valkey is
  unreachable, reads and scrape triggers fail closed (503) rather than falling
  back to a per-instance cache. Point the backend at it with
  `QUARKUS_REDIS_HOSTS=redis://<host>:6379`.
- **A mounted `platform-config` file**, supplied via
  `QUARKUS_CONFIG_LOCATIONS=/config/platform-config.yaml`. Nothing is baked
  into the image on purpose, so changing the watched apps is a config edit,
  not an image rebuild. See [`configuration.md`](configuration.md) for every
  key. The `k8s-image` current source auto-configures from the in-cluster
  ServiceAccount (Fabric8) and needs the RBAC component above; it has no
  out-of-cluster story. `ssh-os-release` instead needs its private key and
  known-hosts/host-key mounted (see
  [`configuration.md`](configuration.md#type-ssh-os-release-current--tier-b-requires-ssh-access))
  and no Kubernetes RBAC at all.

## MCP endpoint authentication

If you turn on MCP endpoint authentication (`MCP_OIDC_ISSUER` +
`MCP_OIDC_AUDIENCE`, see [`configuration.md`](configuration.md#mcp-endpoint-authentication)),
it has two consequences for anything sitting in front of the backend:

- **The shared `HTTPRoute` needs one added rule.** [`docs/adr/0002`](adr/0002-mcp-endpoint-under-api.md)'s
  "no ingress change needed" holds only while the endpoint is unauthenticated.
  RFC 9728 protected-resource metadata is served at the host root
  (`/.well-known/oauth-protected-resource/api/mcp`), outside the `/api`
  prefix — the existing `/api` PathPrefix rule does not cover it, and the
  catch-all `/` rule would hand it to the frontend instead of the backend.
  Add a PathPrefix rule sending `/.well-known/oauth-protected-resource` to
  the backend, unstripped:

  ```yaml
  apiVersion: gateway.networking.k8s.io/v1
  kind: HTTPRoute
  metadata:
    name: platformup2date
  spec:
    rules:
      - matches:
          - path:
              type: PathPrefix
              value: /api
        backendRefs:
          - name: platformup2date-backend
            port: 8080
      - matches:
          - path:
              type: PathPrefix
              value: /.well-known/oauth-protected-resource
        backendRefs:
          - name: platformup2date-backend
            port: 8080
      - matches:
          - path:
              type: PathPrefix
              value: /
        backendRefs:
          - name: platformup2date-frontend
            port: 80
  ```

  The metadata path is not relocated under `/api` — fighting the well-known
  convention would break client-side URL derivation, so the extra rule is
  required rather than optional.

- **Any edge proxy in front of the host must get out of MCP's way.** If
  `oauth2-proxy` (or similar) still fronts the host for the web UI/REST
  interim posture below, it must bypass `/api/mcp` and
  `/.well-known/oauth-protected-resource*` — e.g. oauth2-proxy's
  `--skip-auth-route` — or it intercepts the challenge/discovery requests
  before the backend can answer them, breaking native MCP client
  authentication.

With MCP endpoint authentication off (`MCP_OIDC_ISSUER` unset, the default),
neither change applies and the endpoint behaves exactly as described in
[ADR 0002](adr/0002-mcp-endpoint-under-api.md).

### Interim posture for the web UI and REST API

Only the MCP endpoint authenticates its own callers. The web UI and the REST
API (`/api/v1`) have no in-app authentication — in-app web authentication is
a planned separate feature. Until it lands, protect them the same way you
would have protected the whole host before this feature existed: an edge
proxy such as `oauth2-proxy` in front of the host, or a private network. If
you turn on MCP endpoint authentication and keep such a proxy for the UI/REST,
remember the bypass rules above so the two auth mechanisms don't collide.

## Metrics

The backend exposes a Prometheus scrape endpoint at `/metrics`, hand-rendered
in the Prometheus text exposition format (no Micrometer). Four metric
families are exported:

- `pu2d_application_info` — fleet membership + current/latest version strings.
- `pu2d_version_drift_level` — how far the deployed version is behind latest
  (`0`=current, `1`=patch, `2`=minor, `3`=major; a pre-release difference
  reports as `1`, while a difference in build metadata only is ignored and
  reports as `0`). Calver apps grade by the category of the changed token:
  year → `3`, month/week/day → `2`, micro/modifier → `1` (see
  [`configuration.md`](configuration.md#calver-format)).
- `pu2d_scrape_last_success_timestamp_seconds` — per-(app, side) freshness.
- `pu2d_scrape_last_failure_timestamp_seconds` — per-(app, side) failures.

```
# HELP pu2d_version_drift_level How far the deployed version is behind latest (0=current, 1=patch, 2=minor, 3=major)
# TYPE pu2d_version_drift_level gauge
pu2d_version_drift_level{app="argo-cd"} 3
pu2d_version_drift_level{app="git-tea"} 0
```

### Scraping with Prometheus Operator

Point a `ServiceMonitor` at the backend `Service`'s HTTP port:

```yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: platformup2date
  labels:
    release: prometheus  # match your Prometheus Operator's serviceMonitorSelector
spec:
  selector:
    matchLabels:
      # These match the backend Service shipped in deploy/k8s/base — the
      # component label matters, or Prometheus also scrapes the frontend
      # Service (same name label, same `http` port name, no /metrics).
      app.kubernetes.io/name: platformup2date
      app.kubernetes.io/component: backend
  endpoints:
    - port: http
      path: /metrics
      interval: 1m
```

### Example alert rules

I believe in you! You can figure it out, gambare!

## Grafana dashboard

A default dashboard ships in this repo at [`grafana/platform-up-2-date.json`](../grafana/platform-up-2-date.json).
It's deliberately as close a replica of the frontend as possible. If you already
run a full Prometheus/Grafana monitoring stack, it might make sense to _not_
deploy the frontend — although it's quite pretty imo.

![The bundled Grafana dashboard](img/grafana-dashboard.png)

## Container images

Published to GHCR on every merge to `main` (`:edge`, moving) and on every
`v*` tag (`:X.Y.Z` / `:X.Y` / `:X` / `:latest`):

- `ghcr.io/sreyardship/platformup2date/backend`
- `ghcr.io/sreyardship/platformup2date/frontend`

The frontend image injects `API_BASE_URL` into `window._env_` at container
start (`docker-entrypoint.d/40-env-config.sh`), not at build time — set it as
a plain environment variable on the container/Pod, pointed at wherever the
backend is actually reachable from a browser.
