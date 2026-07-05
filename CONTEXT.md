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
(a *k8s-image* source). The *latest* version comes from an upstream source —
GitHub Releases (a *github-release* source), or the published image tags of an
OCI-spec container registry (an *oci-registry* source, named for the substrate
it scans, like *github-release*). A registry has no native "release" concept, so
the *oci-registry* source treats the largest semver tag in the repository's tag
list as the latest release — the same largest-semver selection *github-release*
applies (see `docs/adr/0010`). Exactly one current source per Application.
_Avoid_: Upstream endpoint (a k8s-image source is the cluster's own state, not
upstream), probe; "image" alone (a k8s-image current source reads the *deployed*
tag, an oci-registry latest source reads *published* tags — different sides)

**Version scheme**:
How an Application's version strings are interpreted and compared — `semver`
(the default) or `calver`. Declared once per Application and shared by its
current and latest sources, so the two are always commensurable. It governs both
ordering ("is it behind?") and how Drift severity is assigned. Comparing across
schemes — or across two different calver formats — is a configuration error,
never a silent guess.
_Avoid_: Version format, versioning style, version type, coercion

**Manual scrape** (a.k.a. **scrape trigger**):
A scrape requested on demand by a human or agent, as opposed to the automatic
scrape that happens lazily when the snapshot is stale.
_Avoid_: Force refresh, rescan

**Drift**:
How far an Application's current version is behind its latest upstream release,
graded by severity: PATCH, MINOR, or MAJOR. Severity is defined under both
Version schemes — by changed component for semver, and by the category of the
changed calver token for calver (year → MAJOR, month/week/day → MINOR,
micro/modifier → PATCH).
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
The shared, cluster-wide snapshot a scrape produces — the observed Applications
(per (app, side): last-known value, last-success time and last-failure time; see
*Side freshness*), the time of the last scrape attempt, and the scrape budget. See
`docs/adr/0003` (state lives in Valkey) and `docs/adr/0019` (it records observation
state, not successes only).
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

**Resolution** (**Resolved** / **Unresolved**):
Whether a monitored Application currently has a known version on *both* sides. An
Application is *Resolved* when both `current` and `latest` have at least one past
successful read, so the two are comparable and *Drift* is defined. It is
*Unresolved* when at least one side has never been read successfully — so there is
no version to show on that side ("—") and Drift is *undefined*, not `NONE`.
Unresolved is a first-class board state (shown as "Unknown"), orthogonal to Drift:
a status is *either* a Drift grade *or* Unknown, never both. See `docs/adr/0001`
for what an Application is; contrast *Side freshness*, which is about how current a
value is, not whether one exists.
_Avoid_: Unknown drift (Drift is undefined, not a drift grade), missing, N/A,
never-scraped (that is one cause of Unresolved, not the state itself)

**Side freshness**:
What the UI shows about *how current each side of an Application's version pair
is*, tracked independently for every (Application, side) pair — each app's
`current` and `latest` age separately, and so do two different apps' `current`
sides — because a targeted scrape can refresh one (app, side) without the others. Two facts per side: the *read time* — when
that side's displayed value was last *successfully* read (its "as-of"), which
moves only when a new value is written — and, if the most recent attempt for that
side *failed*, a *failed-refresh* marker carrying when that failure happened. A
side can therefore show a value that succeeded long ago together with a recent
failure. Distinct from the fleet-wide last-scrape-attempt clock in *Scrape
state*, which governs staleness for the whole fleet, not one side.
_Avoid_: Last scrape (ambiguous — fleet clock vs. per-side), timestamp,
freshness (unqualified)

**Failed scrape**:
A scrape attempt on a *side* whose read did not resolve — the newest thing that
happened to that side was a failure. It is a property of the *read*, never of the
Application: the app itself may be perfectly healthy and reachable by everyone
else; what failed is *our attempt to observe its version*. Surfaces as the
*failed-refresh* marker in *Side freshness* and as the `list_applications_with_
failed_scrapes` MCP tool. Note this is narrower than *Unresolved*: an Unresolved
app can be either *failed* (a read was tried and failed) or merely *pending* (no
read attempted yet) — only the former is a Failed scrape.
_Avoid_: Failing/unhealthy application (the app is not what failed), broken app,
down

**Backend unavailable**:
The state of a Surface that cannot obtain the scrape state at all — its request
to the backend got no answer (*unreachable*) or an error answer (*API error*).
A property of the surface-to-backend connection, never of any Application or
scrape: contrast *Failed scrape*, where the backend is fine but its read of one
side failed. While unavailable, a surface with nothing yet loaded shows the
unavailability itself — never an empty fleet, which would be a lie — and a
surface that has already shown data keeps the last loaded snapshot visible with
the unavailability marked, mirroring how *Side freshness* keeps a last-known
value alongside a failed refresh.
_Avoid_: Backend down (unproven — only our request failed), offline, connection
lost, empty fleet / zero applications (the lie this term exists to prevent)

**Changelog link**:
The per-Application link to the release notes of the *latest* upstream release,
offered on every Surface (the board's changelog icon, the REST payload, and the
MCP tools — so an agent can read release notes for breaking changes before
update work). It is a *projection*: derived on read from an operator-configured
URL template and the Application's stored latest version — never observed or
stored by a scrape (contrast a version value, which is an observation). One
template per Application, regardless of which latest source kind the app uses;
its placeholders follow the Application's Version scheme (semver.org component
names for semver, the app's declared calver-format tokens for calver). No
template, or no known latest version, means no link.
_Avoid_: release URL, changelog observation, html_url

**Surface**:
A client-facing entry point that reads scrape state or requests a manual scrape —
the REST API, the web UI's Refresh button, the MCP tools, and the Prometheus
metrics endpoint (with the Grafana dashboard it feeds). Every surface
projects the same shared scrape state and holds none of its own, so any replica
can serve any surface. See `docs/adr/0003` and `docs/adr/0004`.
_Avoid_: Endpoint, channel, interface, transport
