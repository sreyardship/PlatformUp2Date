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
A single pass that fetches every monitored Application's current and latest
version from its upstream endpoints, producing one coherent snapshot.
_Avoid_: Poll, fetch, sync, crawl

**Manual scrape** (a.k.a. **scrape trigger**):
A scrape requested on demand by a human or agent, as opposed to the automatic
scrape that happens lazily when the snapshot is stale.
_Avoid_: Force refresh, rescan

**Drift**:
How far an Application's current version is behind its latest upstream release,
graded by severity: PATCH, MINOR, or MAJOR.
_Avoid_: Lag, delta, staleness (staleness refers to the snapshot, not a version)

**Scrape budget**:
The number of manual scrapes permitted within a rolling time window
(default 10 per hour), shared across all surfaces and all replicas.
_Avoid_: Quota, rate cap, throttle

**Scrape state**:
The shared, cluster-wide snapshot a scrape produces — the cached Applications,
the time of the last scrape attempt, and the scrape budget. See
`docs/adr/0003`.
_Avoid_: Cache (the state is more than a cache)

**Outcome**:
The result of requesting a manual scrape: SCRAPED (it ran), RATE_LIMITED (budget
exhausted), or IN_PROGRESS (another replica is already scraping).
_Avoid_: Status code, result
