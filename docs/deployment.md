# Deploying to Kubernetes

The README's quick start (`compose.quickstart.yml`) is for trying the tool out
locally. This page is for running it for real, on your own cluster.

## What the backend needs

- **A reachable Valkey (or Redis-API-compatible) instance.** All scrape state —
  the observed Applications, the fleet-wide staleness clock, and the manual-scrape
  budgets — lives centrally in Valkey, not in the JVM
  (ADR [0003](adr/0003-scrape-state-centralised-in-valkey.md)). This makes every
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

Nothing ships in this repo — no `PrometheusRule`, no rules file. The
snippet below is a starting point to adapt and apply yourself alongside your
own Prometheus/Alertmanager setup.

```yaml
groups:
  - name: platform-up-2-date
    rules:
      - alert: AppOutdated
        expr: pu2d_version_drift_level > 0
        for: 1h
        labels:
          severity: warning
        annotations:
          summary: "{{ $labels.app }} is behind its latest release"

      - alert: AppMajorVersionBehind
        expr: pu2d_version_drift_level >= 3
        for: 1h
        labels:
          severity: critical
        annotations:
          summary: "{{ $labels.app }} is a major version behind latest"
```

For the actual current/latest version strings (more detail than the gauge
carries), use the frontend or `GET /api/v1/version`.

## Grafana dashboard

A default dashboard ships in this repo at [`grafana/platform-up-2-date.json`](../grafana/platform-up-2-date.json)
(UID `pu2d-version-drift`, title "Application Version Drift"). It's
deliberately a single panel: a stat grid with one color-coded tile per
application (green/yellow/orange/red for 0/1/2/3 drift), driven by
`max by(app) (pu2d_version_drift_level)`.

The `pu2d_application_info` and scrape-timestamp metric families (see above)
aren't visualized in the shipped dashboard — build panels/tables against
them yourself if you want fleet counts, version strings, or staleness views;
they're on the same `/metrics` endpoint the drift panel already scrapes.

### Importing

Via the UI: **Dashboards → New → Import**, upload
`grafana/platform-up-2-date.json`, pick your Prometheus datasource.

Via file provisioning, point a dashboard provider at a folder containing the
JSON:

```yaml
# /etc/grafana/provisioning/dashboards/platformup2date.yaml
apiVersion: 1
providers:
  - name: platformup2date
    type: file
    options:
      path: /var/lib/grafana/dashboards   # folder holding platform-up-2-date.json
```

## Container images

Published to GHCR on every merge to `main` (`:edge`, moving) and on every
`v*` tag (`:X.Y.Z` / `:X.Y` / `:X` / `:latest`):

- `ghcr.io/sreyardship/platformup2date/backend`
- `ghcr.io/sreyardship/platformup2date/frontend`

The frontend image injects `API_BASE_URL` into `window._env_` at container
start (`docker-entrypoint.d/40-env-config.sh`), not at build time — set it as
a plain environment variable on the container/Pod, pointed at wherever the
backend is actually reachable from a browser.
