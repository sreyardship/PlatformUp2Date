# [05] MCP surfaces freshness and a failed-scrapes tool

Status: done
Type: AFK

## Plan
See `../plan.md` for the shared architecture. Glossary: `../../../CONTEXT.md`
(*Failed scrape*, *Resolution*, *Side freshness*); design record: `../../../docs/adr/0019-...`.

## What to build
Give the MCP agent the same freshness truth the board has, and a way to find what is failing —
so a targeted scrape that silently failed doesn't look like "no change".

- `ApplicationView` gains: nullable `current`/`latest` versions (an Unresolved side is null),
  a `resolution`, and per-side read-time + failure-time (absolute instants). The reason is NOT
  carried — it stays in the logs (out of scope by decision); the view reports *that* and *when*
  a side failed, not *why*.
- `get_application` therefore tells the full per-side freshness story for one app, including
  Unresolved and failed-refresh states.
- New tool `list_applications_with_failed_scrapes`: returns apps where **at least one side's
  newest attempt was a failure** (the `failedRefresh()` predicate) — i.e. had-a-value-then-failed
  OR never-succeeded-and-failed. A **pending** side (never attempted, no failure) is **excluded**;
  calling a scrape that never ran "failed" would be a lie. Its description must frame this as a
  *scrape* that failed, never a failing/unhealthy application.
- `list_outdated_applications` is unchanged: it stays the drift list and does not pick up
  Unresolved apps (they have no drift).

## Architectural surface
- Use cases: `getApplications` (adapter filters on domain predicates; no new port method)
- Ports: none new
- Adapters: `ApplicationMcpTools`, `ApplicationView`

## Acceptance criteria
- [ ] `ApplicationView` carries nullable versions + resolution + per-side read/failure instants;
      no failure reason field.
- [ ] `get_application` returns the freshness fields for resolved, failed-refresh, and Unresolved
      apps.
- [ ] `list_applications_with_failed_scrapes` returns exactly the sides whose newest attempt
      failed (had-value-then-failed and never-succeeded-and-failed), and **excludes** pending
      never-attempted sides — covered by a unit/adapter test over the four-state matrix.
- [ ] `list_outdated_applications` behaviour is unchanged (regression).
- [ ] The new tool's description attributes failure to the scrape, not the application.
- [ ] `gradle test` and the integration suite pass.

## Blocked by
`03-unknown-apps-appear.md`. (Independent of `04`; the two can proceed in parallel.)

## User stories covered
_No PRD supplied; design source is `../../../docs/adr/0019-...` and `../../../CONTEXT.md`._
