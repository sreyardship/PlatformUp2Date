# PlatformUp2Date

Monitors deployed platform applications against their latest upstream releases,
showing whether each is up-to-date (green) or behind (red).

## Language

**Application**:
One upstream project being monitored (e.g. Grafana, Argo CD, a Helm chart),
with an observed *current* version and a *latest* upstream release. See
`docs/adr/0001`.
_Avoid_: Service, app target, upgrade unit

**Scrape**:
A pass that reads version(s) from version sources and merges the result into the
shared scrape state. Comes in two shapes — a *full scrape* and a *targeted
scrape* (see below).
_Avoid_: Poll, fetch, sync, crawl

**Full scrape**:
A scrape that fetches every monitored Application's current and latest version,
producing one coherent snapshot and resetting the fleet-wide staleness clock.
This is the scrape the automatic (lazy) refresh and the UI's Refresh button run.
_Avoid_: Whole scrape, complete scrape

**Targeted scrape**:
A scrape of a chosen set of *scrape targets* rather than the whole fleet. It
fetches only the requested sources, field-level merges them into the existing
snapshot, and deliberately leaves the fleet-wide staleness clock untouched (it
refreshed only some apps, so the fleet is no fresher than before). Exists to cut
load on upstream servers (e.g. GitHub) during update work — refresh one app's
current version without re-hitting every upstream.
_Avoid_: Partial scrape, single scrape, micro-scrape

**Scrape target**:
One unit of a targeted scrape: an Application paired with a *side* to read —
`current`, `latest`, or `both`. A targeted scrape carries a list of these and may
mix sides across apps in one call. A target naming an unmonitored app fails on
its own without sinking the rest of the call.
_Avoid_: Scrape request, scrape item

**Version source**:
Where a scrape reads a version from. An Application's *current* version comes
from one of two kinds of source — an HTTP version endpoint exposed by the
running app, or the deployed container image tag read from the Kubernetes API
(a *k8s-image* source). The *latest* version comes from an upstream source
(GitHub Releases, a Helm chart index). Exactly one current source per
Application.
_Avoid_: Upstream endpoint (a k8s-image source is the cluster's own state, not
upstream), probe

**Manual scrape** (a.k.a. **scrape trigger**):
A scrape requested on demand by a human or agent, as opposed to the automatic
scrape that happens lazily when the snapshot is stale.
_Avoid_: Force refresh, rescan

**Drift**:
How far an Application's current version is behind its latest upstream release,
graded by severity: PATCH, MINOR, or MAJOR.
_Avoid_: Lag, delta, staleness (staleness refers to the snapshot, not a version)

**Scrape budget**:
The number of manual scrapes permitted within a rolling time window, shared
across all surfaces and all replicas. There are two independent budgets: the
*full-scrape budget* (default 10 per hour) and a separate, larger *targeted-scrape
budget*, so agent-driven targeted scrapes during update work do not starve the
UI's full Refresh. Both protect the same upstream servers; they are accounted
separately. See `docs/adr/0006`.
_Avoid_: Quota, rate cap, throttle

**Scrape state**:
The shared, cluster-wide snapshot a scrape produces — the cached Applications,
the time of the last scrape attempt, and the scrape budget. See
`docs/adr/0003`.
_Avoid_: Cache (the state is more than a cache)

**Outcome**:
The call-level result of requesting a manual scrape: SCRAPED (it ran),
RATE_LIMITED (budget exhausted), or IN_PROGRESS (another replica is already
scraping). Describes the request as a whole, not any individual app — for that
see *Target result*.
_Avoid_: Status code, result

**Target result**:
The per-app fate within a scrape that did run (Outcome SCRAPED): whether each
Application's read succeeded or FAILED, with a reason and the side(s) read. Both
full and targeted scrapes report these, so a caller learns exactly which app
fell out — not just how many. Distinct from Outcome, which is the whole call.
_Avoid_: Per-target outcome, app status

**Surface**:
A client-facing entry point that reads scrape state or requests a manual scrape —
the REST API, the web UI's Refresh button, and the MCP tools. Every surface
projects the same shared scrape state and holds none of its own, so any replica
can serve any surface. See `docs/adr/0003` and `docs/adr/0004`.
_Avoid_: Endpoint, channel, interface, transport
