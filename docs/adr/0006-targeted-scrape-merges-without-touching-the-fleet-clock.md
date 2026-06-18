# A targeted scrape merges into the shared snapshot without touching the fleet clock, and draws on its own budget

Until now a *scrape* meant one thing: a **full scrape** that fetches every
monitored Application's current and latest version, writes one coherent snapshot,
and resets the fleet-wide staleness clock (`ScrapeSnapshot.lastAttemptAt`). Every
surface — the REST API, the UI's Refresh, the MCP `trigger_scrape` tool — runs
that same whole-fleet pass, and all manual triggers share a single rolling-window
budget (default 10/hour, see [0003](0003-scrape-state-centralised-in-valkey.md)).

That is wasteful for the dominant agent workflow. When an agent upgrades one app
and wants to confirm its deployed version moved, a full scrape re-hits *every*
upstream — every deployed app and, more expensively, the rate-limited GitHub
Releases API for every monitored repo — to learn one fact. We add a **targeted
scrape**: a call carries a list of *scrape targets*, each an `(app, side)` pair
(`current`, `latest`, or `both`), and fetches only those sources.

Two decisions in this ADR are non-obvious and costly to walk back.

**The targeted result field-level merges into the snapshot and leaves
`lastAttemptAt` untouched.** A targeted scrape reads the current snapshot,
splices the freshly-read side(s) of each target over the matching app, and writes
the whole snapshot back re-supplying the *existing* `lastAttemptAt`. The fleet
clock is deliberately not advanced: only some apps were refreshed, so the fleet
is no fresher than before, and pretending otherwise would suppress the automatic
full scrape that keeps the *rest* of the fleet honest. The snapshot stays a single
Valkey key with whole-value overwrite; every mutation — full or targeted —
serializes through the one existing global `scrape:lock`
([0003](0003-scrape-state-centralised-in-valkey.md)), so a read-modify-write merge
cannot lose updates. A targeted scrape that loses the lock returns `IN_PROGRESS`,
exactly like the full one.

**Targeted scrapes draw on a separate, larger budget.** Full and targeted scrapes
each have their own rolling-window budget and their own Valkey ZSET. An agent
firing many one-source targeted scrapes during an upgrade therefore cannot starve
the UI's full-Refresh budget, and the two telemetry numbers (`remaining`,
`retryAfterSeconds`) each mean exactly one thing on exactly one surface. Both
budgets still protect the same upstream servers; they are merely accounted apart.
This is why the targeted scrape is a *new* MCP tool and a *new* HTTP endpoint
rather than an optional argument on `trigger_scrape` / `POST /api/v1/scrape`: one
operation whose budget silently changes with an argument would make every
rate-limit number ambiguous.

## Considered Options

- **One shared budget, 1 slot per call** — simplest, still caps GitHub exposure.
  Rejected: an agent's targeted scrapes and the UI's Refresh would compete for the
  same 10/hour, so heavy update work could lock a human out of refreshing. The
  separate budget is the whole point — decouple agent load from UI load.
- **Targeted scrape as an optional `targets` arg on the existing
  full-scrape tool/endpoint** — matches the literal "extend the tool" framing.
  Rejected: with two budgets, the existing tool's telemetry and description would
  have to hedge on which budget every number refers to. Two clean tools keep each
  contract single-meaning.
- **Per-app `lastAttemptAt` (per-app freshness)** — would let a targeted scrape
  honestly record that only some apps are fresh. Rejected for now: it remodels
  `ScrapeSnapshot` and the staleness logic for a payoff this feature does not
  need. Leaving the fleet clock frozen is correct, just coarse.
- **Separate lock so targeted and full scrapes run concurrently** — rejected: they
  both rewrite the one snapshot key, so concurrency reintroduces the lost update
  unless storage gains an atomic server-side merge — a much larger change.
- **Return the refreshed versions inline from the targeted scrape** — considered
  as a round-trip saving. Rejected: the load we protect is *upstream*, and a
  follow-up `get_application` reads the Valkey snapshot, never GitHub. The tool
  stays telemetry-only, consistent with `trigger_scrape`.

## Consequences

- A targeted value lives only until the next staleness-triggered **full scrape**
  overwrites it. Because the targeted scrape froze the fleet clock, the snapshot
  may already be older than the scrape-interval, so the very next plain
  `getApplications()` read can trigger a full scrape that re-reads every app and
  replaces the targeted value. Targeted scrapes refresh a value *now*; they do not
  pin it.
- `ScrapeResult`/`ScrapeStatus` grow per-app **target results** — a shared
  `{name, side, reason}` record listing which apps failed and why — and the full
  scrape adopts the same record, so it too now reports *which* apps fell out, not
  just how many. Both surfaces' bodies gain this field.
- A target naming an unmonitored app fails on its own (`FAILED`, reason
  "not monitored") without sinking the rest of the call; a `current`- or
  `latest`-only target for an app not yet in the snapshot falls back to fetching
  *both* sides once, so a complete entry can be written.
- There are now two budgets to configure and two ZSET keys in Valkey. The
  targeted budget's default must be set larger than the full-scrape budget to
  serve its purpose.
