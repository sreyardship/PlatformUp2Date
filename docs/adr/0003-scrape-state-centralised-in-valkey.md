# All scrape state is centralised in Valkey; the app holds none

Adding an on-demand manual scrape (HTTP `POST /api/v1/scrape` and the
`trigger_scrape` MCP tool) forced the question of where scrape state lives. We
moved **all** of it — the cached Applications, the last-scrape-attempt clock, and
the manual-scrape budget — out of the JVM and into Valkey, accessed through
outbound ports with a Valkey adapter. `ApplicationVersionService` now keeps no
in-memory cache, no `lastAttemptAt`, and no `synchronized` guard; every read and
trigger goes through Valkey. This makes the whole cluster share one snapshot, one
staleness clock, and one budget, so a manual scrape on any replica updates what
everyone serves and a scrape happens at most once cluster-wide per window.

## Considered Options

- **Per-instance in-memory state (the prior design)** — rejected: with more than
  one replica, each holds its own cache and clock, so automatic scrapes multiply
  by replica count and a manual scrape only refreshes the replica that served it.
- **Share only the manual-scrape budget, keep cache/clock local** — rejected:
  caps manual spam but leaves the automatic scrape path uncoordinated, so the
  GitHub-rate-limit protection the budget exists for is undermined by N replicas
  scraping on their own schedules.
- **Centralise everything in Valkey (chosen)** — one coherent snapshot and one
  budget across replicas, at the cost of a hard infra dependency.

## Consequences

- **Valkey is a hard dependency, and the app fails closed.** If Valkey is
  unreachable, reads (`GET /version`) and triggers return 503 — there is no
  per-instance fallback path to maintain or test. A Valkey readiness check gates
  the service.
- **Thundering herd is prevented with a distributed lock.** Before scraping
  (automatic or manual) a replica acquires a Valkey lock; losers serve the shared
  snapshot. A manual trigger that loses the lock returns `IN_PROGRESS` and spends
  no budget slot.
- **The budget is a rolling-window counter in Valkey** (ZSET evaluated by an
  atomic Lua script), so the limit holds exactly across replicas. Count and
  window are configurable (default 10 / 1h).
- Contract-first was *not* revived for the new HTTP endpoint; it is hand-written
  like `VersionController`, pending a separate cleanup PR.
