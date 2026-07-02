# [04] Per-(app, side) freshness metrics

Status: done
Type: AFK

## Plan
See `../plan.md` for the shared architecture. Glossary: `../../../CONTEXT.md`
(*Side freshness*, *Resolution*); design record: `../../../docs/adr/0019-...`.

## What to build
Expose the per-side clocks as Prometheus gauges so staleness and stuck scrapes are alertable,
and stop reporting Unresolved apps as up-to-date drift.

- Emit two new gauges from the observation state, keyed by `{app, side}` (side = `current` /
  `latest`):
  - `pu2d_scrape_last_success_timestamp_seconds` — unix seconds of the side's last successful
    read; a never-succeeded side has **no series** (its absence is the Unresolved signal).
  - `pu2d_scrape_last_failure_timestamp_seconds` — unix seconds of the side's last failed read;
    absent when no failure recorded. A stuck side is the alertable predicate
    `last_failure > last_success`.
- Drop Unresolved apps from `pu2d_version_drift_level` entirely (no series) rather than emitting
  a misleading `0`/`NONE` — drift is undefined without both values. Resolved apps' drift series
  is unchanged.
- Keep the renderer a thin projection: all "is this resolved / did the newest attempt fail"
  meaning stays in the domain predicates from slices 02–03.

## Architectural surface
- Use cases: `getApplications`
- Ports: none new
- Adapters: `MetricsController`, `PrometheusDriftRenderer`

## Acceptance criteria
- [ ] `pu2d_scrape_last_success_timestamp_seconds{app,side}` rendered for each resolved side
      with the correct unix-seconds value.
- [ ] `pu2d_scrape_last_failure_timestamp_seconds{app,side}` rendered only for sides with a
      recorded failure.
- [ ] A never-succeeded side emits no success series; an Unresolved app emits no
      `pu2d_version_drift_level` series.
- [ ] Resolved apps' `pu2d_version_drift_level` output is unchanged (regression).
- [ ] Renderer unit tests + `MetricsEndpointIT` cover the above; `gradle test` and the
      integration suite pass.

## Blocked by
`03-unknown-apps-appear.md`.
