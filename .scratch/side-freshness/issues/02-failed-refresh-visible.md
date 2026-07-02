# [02] Failed-refresh is visible

Status: done
Type: AFK

## Plan
See `../plan.md` for the shared architecture. Glossary: `../../../CONTEXT.md`
(*Side freshness*, *Failed scrape*); design record: `../../../docs/adr/0019-...`.

## What to build
Make a failed newest read visible without losing the last good value. When a side's source
throws, the board keeps showing the old version and its read time, but adds a marker saying
the most recent refresh failed and when.

- Unify both scrape paths onto a per-side resolve-stamp-**merge-over-prior** model: each side
  is resolved independently against its source; on success the side's value + last-success are
  updated; on failure the side keeps its prior value + last-success and gains a last-success
  time stamp of the failure (`lastFailureAt`) from the injected `Clock`. This replaces the
  full-scrape path's current write-fresh/atomic-per-app behaviour, and reuses the existing
  targeted-scrape merge seam.
- `SideObservation.failedRefresh()` is the single predicate: last-failure present and newer
  than last-success (or no success yet). It is what the UI marker (and later the metric and MCP
  tool) branch on.
- The fleet-wide `lastAttemptAt` still advances only on a full scrape (unchanged); a targeted
  scrape still leaves it untouched.
- `GET /version` per-side object now also carries `failedAt` (absolute instant, nullable).
- Frontend shows a muted "refresh failed Xm ago" marker on a side whose newest attempt failed,
  alongside the still-displayed old value and its read time. Relative + absolute-local tooltip,
  same conventions as slice 01.

## Architectural surface
- Use cases: `triggerScrape`, `targetedScrape` (per-side resolve + merge-over-prior + failure stamp)
- Ports: `ScrapeStateStore` (last-failure now populated)
- Adapters: `ValkeyScrapeStateStore`, `VersionController` + `ApplicationStatus`,
  frontend `ApplicationRow`

## Acceptance criteria
- [ ] A full scrape where one side's source throws keeps that side's prior value + last-success
      and stamps `lastFailureAt`; the other side updates normally — unit test with fixed clock
      and a throwing source double.
- [ ] `failedRefresh()` truth table unit-tested (fresh / failed-refresh / never-succeeded-failed).
- [ ] Snapshot round-trips a side with a value + last-success + a newer last-failure — integration test.
- [ ] `GET /version` carries `failedAt` per side; null when the newest attempt succeeded.
- [ ] A row renders the failed-refresh marker only when the failure is the newest event, while
      still showing the old value and read time — frontend test.
- [ ] Fleet `lastAttemptAt` advances on full scrape only (regression covered).
- [ ] `gradle test`, integration suite, and `yarn test` pass.

## Blocked by
`01-read-time-on-each-version.md`.
