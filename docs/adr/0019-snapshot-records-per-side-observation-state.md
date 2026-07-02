# The snapshot records per-(app, side) observation state, not only successful versions

To let the UI, MCP and metrics say *when each side of each Application was last
read* and *whether the newest read failed*, the shared Valkey snapshot stops being
"the set of successfully-resolved Applications" and becomes a record of
**observation state per (application, side)**: for each of an app's `current` and
`latest` sides we keep the last-known value (which may be absent), the time of its
last *successful* read, and the time of its last *failed* read. Freshness is
therefore tracked independently for every (app, side) pair — Grafana's `current`
and Grafana's `latest` age separately, as do two different apps' `current` sides —
which a single fleet-wide clock cannot express once targeted scrapes refresh one
side without the other.

This amends the premise recorded in `docs/adr/0003`: all scrape state still lives
in Valkey, but "the cached Applications" now means observed state (successes *and*
the newest failure), not successes alone. It also relaxes the `VersionApplication`
non-null invariant so a monitored app can appear with a value on one side and none
on the other — the *Unresolved* state (see `CONTEXT.md`: *Resolution*, *Side
freshness*, *Failed scrape*).

## Considered Options

- **Keep failures transient (chosen against).** Failures already flow back in the
  trigger response (`ScrapeResult.failed`, `TargetResult`). But that only reaches
  the caller who triggered the scrape — an *automatic* lazy scrape, or a failure on
  another replica, is seen by nobody. A user loading the board via a different
  replica would never learn a side is failing. Persisting in the shared snapshot is
  the only place a not-triggered-by-me failure can surface.
- **Keep the non-null invariant; hide never-scraped apps (chosen against).** Simpler
  schema, but a freshly-added, misconfigured app would be silently invisible —
  exactly the app a platform engineer most needs to see. We chose to show it as
  *Unresolved* instead.
- **Record per-(app, side) observation state (chosen).** One coherent shared truth
  that every surface projects, at the cost of a richer snapshot shape and nullable
  sides.

## Consequences

- **Drift is undefined for Unresolved apps**, not `NONE`. They are dropped from
  `pu2d_version_drift_level` rather than reported as up-to-date, and render as
  "Unknown" on the board (a status is *either* a Drift grade *or* Unknown).
- **New per-(app, side) gauges** expose the clocks directly:
  `pu2d_scrape_last_success_timestamp_seconds` and
  `pu2d_scrape_last_failure_timestamp_seconds`. A stuck side is the alertable
  predicate `last_failure > last_success`; a never-succeeded side is an absent
  success series.
- **Failure reason is not persisted** — it stays in the logs. The snapshot records
  *that* and *when* a side failed, not *why*; the target audience fixes causes in
  gitops config with the logs to hand.
- **A brief *pending* state exists** (added to config, no scrape run yet): Unresolved
  but not failed. It is excluded from `list_applications_with_failed_scrapes`, which
  reports only sides whose newest attempt was a failure.
