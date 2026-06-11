# Applications are upstream components; delivery channels are monitored separately

A monitored Application is one upstream project (Grafana, Prometheus, istiod, Ceph),
even when several of them are delivered together by one Helm chart. Its red/green
status compares the observed running version against the project's *own* newest
upstream release — never capped by what the delivering chart currently ships. The
delivery channel itself (e.g. the kube-prometheus-stack chart) is a separate
Application with its own current/latest (deployed chart version vs chart repository
index), because charts and the apps they bundle drift independently: a chart can
update three times without bumping an image, and an image tag can be pinned ahead
of the chart.

## Considered Options

- **Upgrade unit as the Application** (one card per chart/pin, so red always maps
  1:1 to a single repo edit). Rejected: it hides which actual software is behind,
  and conflates "the chart moved" with "the app moved."
- **Pure upstream per component without tracking the chart** — rejected because the
  chart's own staleness is real, independent drift worth a card.

## Consequences

- A component card can be red while the bundling chart has no release shipping that
  version yet. This is deliberate: pinning a newer image tag over the chart's
  default is an accepted upgrade action, so "behind upstream" remains actionable.
- The latest-probe set must include a Helm chart repository (`index.yaml`) probe so
  charts can be monitored as Applications.
- The mapping from a red component to the concrete upgrade action (chart bump vs
  image pin) is platform knowledge and lives outside PlatformUp2Date (in the
  GitOps repo's per-app UPGRADE.md), keeping the monitor substrate-agnostic.
