# [03] Unknown apps appear

Status: done
Type: AFK

## Plan
See `../plan.md` for the shared architecture. Glossary: `../../../CONTEXT.md`
(*Resolution*, *Side freshness*); design record: `../../../docs/adr/0019-...`.

## What to build
Let a monitored app that has never successfully read a side appear on the board as "Unknown"
instead of silently vanishing. This is where the `VersionApplication` non-null invariant is
deliberately relaxed and the Resolution state becomes first-class.

- A `SideObservation` may hold no value (never successfully read). `VersionApplication` no
  longer requires both sides to have a value; add `isResolved()` (both sides have a value) and
  guard `drift()`/`isOld()` so they are only consulted when resolved.
- The scrape persists an app even when a side has never resolved (drop the "upgrade single-side
  cold target to BOTH because half an app cannot be persisted" workaround — half an app is now
  representable). A side that has been attempted and failed carries `lastFailureAt` with no
  value; a side never attempted carries nothing (pending).
- `GET /version` gains a top-level `resolution` (Resolved / Unresolved) and emits `version:
  null` for a side with no value; `drift` is absent/undefined for an Unresolved app rather than
  `NONE`.
- Frontend:
  - Unresolved app renders an "Unknown" status badge (neutral/grey, distinct from the drift
    colours) and "—" for the missing version(s); a failed side still shows its failed-refresh
    marker.
  - Default status sort ranks **Unknown at the very top** (above MAJOR).
  - Version-column sort sinks missing ("—") versions to the **bottom** in both directions
    (a missing value is not "oldest").
  - `SummaryCards` gains an **"Unknown"** stat so Total reconciles with the buckets.

## Architectural surface
- Use cases: `getApplications`, `triggerScrape`/`targetedScrape` (persist Unresolved apps)
- Ports: `ScrapeStateStore` (nullable value round-trip)
- Adapters: `ValkeyScrapeStateStore`, `VersionController` + `ApplicationStatus`,
  frontend `ApplicationRow`/`ApplicationTable`/`SummaryCards`/`drift.js`/`fakeData`

## Acceptance criteria
- [ ] `VersionApplication` accepts a value-less side; `isResolved()` and guarded
      `drift()`/`isOld()` unit-tested; an Unresolved app never reports drift `NONE`.
- [ ] A first scrape that fails a side yields a persisted Unresolved app (not a dropped one) —
      service unit test.
- [ ] Snapshot round-trips an app with a null-value side — integration test.
- [ ] `GET /version` emits `resolution` and `version: null` for the missing side; drift is not
      reported as `NONE` for Unresolved — integration test.
- [ ] Frontend: Unknown badge + "—" render; Unknown sorts to top by status; missing versions
      sort to bottom by version; "Unknown" summary stat present and totals reconcile.
- [ ] `gradle test`, integration suite, and `yarn test` pass.

## Blocked by
`02-failed-refresh-visible.md`.
