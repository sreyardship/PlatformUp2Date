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

## What the backend needs

- **A reachable Valkey (or Redis-API-compatible) instance.** All scrape state —
  the observed Applications, the fleet-wide staleness clock, and the manual-scrape
  budgets — lives centrally in Valkey, not in the JVM. This makes every
  replica serve the same snapshot, but it is a hard dependency: if Valkey is
  unreachable, reads and scrape triggers fail closed (503) rather than falling
  back to a per-instance cache. Point the backend at it with
  `QUARKUS_REDIS_HOSTS=redis://<host>:6379`.
- **A mounted `platform-config` file**, supplied via
  `QUARKUS_CONFIG_LOCATIONS=/config/platform-config.yaml` pointed at a mounted
  `ConfigMap`. Nothing is baked into the image on purpose, so changing the
  watched apps is a ConfigMap edit, not an image rebuild. See
  [`configuration.md`](configuration.md) for every key.
- **A ServiceAccount + ClusterRole**, only if any app uses the `k8s-image`
  current source (Tier B: it reads a workload's running container image tag
  from the Kubernetes API — see [`configuration.md`](configuration.md#type-k8s-image-current--tier-b-requires-cluster-access)).
  The backend's Fabric8 client auto-configures from the in-cluster
  ServiceAccount token; there is no client config in the app itself, only the
  RBAC below:

  ```yaml
  apiVersion: rbac.authorization.k8s.io/v1
  kind: ClusterRole
  metadata:
    name: platformup2date-read-workloads
  rules:
    - apiGroups: ["apps"]
      resources: ["deployments", "statefulsets", "daemonsets"]
      verbs: ["get"]
  ---
  apiVersion: rbac.authorization.k8s.io/v1
  kind: ClusterRoleBinding
  metadata:
    name: platformup2date-read-workloads
  roleRef:
    apiGroup: rbac.authorization.k8s.io
    kind: ClusterRole
    name: platformup2date-read-workloads
  subjects:
    - kind: ServiceAccount
      name: platformup2date
      namespace: <your-namespace>
  ```

  This is cluster-wide read-only `get` access on the three workload kinds — no
  `list`/`watch`, no write verbs, no secrets access. If any app instead uses
  `ssh-os-release`, mount its private key and known-hosts/host-key as a Secret
  volume instead (see [`configuration.md`](configuration.md#type-ssh-os-release-current--tier-b-requires-ssh-access));
  that source needs no Kubernetes RBAC at all.

## Metrics

The backend exposes a Prometheus scrape endpoint at `/metrics`, hand-rendered
in the Prometheus text exposition format (no Micrometer). Four metric
families are exported:

- `pu2d_application_info` — fleet membership + current/latest version strings.
- `pu2d_version_drift_level` — how far the deployed version is behind latest
  (`0`=current, `1`=patch, `2`=minor, `3`=major; a pre-release difference
  reports as `1`, while a difference in build metadata only is ignored and
  reports as `0`).
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
      app.kubernetes.io/name: platformup2date
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
