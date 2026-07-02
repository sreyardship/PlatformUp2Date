# [01] Read time on each version

Status: done
Type: AFK

## Plan
See `../plan.md` for the shared architecture (domain, ports, adapters, test strategy).
Design record: `../../../docs/adr/0019-snapshot-records-per-side-observation-state.md`;
glossary: `../../../CONTEXT.md` (*Side freshness*).

## What to build
The tracer bullet. Establish the per-side observation model and prove it composes end to
end for the common case: an app whose both sides read successfully now shows, under each
version, when that version was last read.

- Introduce a `SideObservation` value object holding an optional version value, an optional
  last-success instant, and an optional last-failure instant, with the invariant that a
  present value implies a present last-success time. This slice only ever produces
  observations with a value + last-success set (no failures, no missing values yet) — the
  last-failure field exists so the persistence/wire container is established once, and is
  populated in slice 02.
- `VersionApplication` carries a `SideObservation` per side instead of a bare
  `VersionValue`. Keep `isOld()`/`drift()` working for the resolved case they still cover.
- A scrape stamps each side's last-success time from the injected `Clock` when the read
  succeeds. Full-scrape write behaviour is otherwise unchanged this slice (still writes the
  freshly scraped apps; merge-over-prior arrives in slice 02).
- Persist the observation shape through the Valkey snapshot DTO (value string + last-success
  + last-failure epoch millis per side; last-failure null this slice).
- `GET /api/v1/version` exposes each side as an object carrying the version and its `readAt`
  absolute instant (raw, no relative math server-side).
- Frontend renders, beneath each version value, a muted relative "read Xm ago" computed
  client-side, with the exact absolute-local time in a hover tooltip. Browser-local timezone;
  no live ticking (recomputed on each fetch).

## Architectural surface
- Use cases: `getApplications`, `triggerScrape`/`targetedScrape` (stamping only)
- Ports: `ScrapeStateStore` (DTO shape; signature unchanged)
- Adapters: `ValkeyScrapeStateStore`, `VersionController` + `ApplicationStatus`,
  frontend `ApplicationRow`/`ApplicationTable`/`versionClient`/`fakeData`

## Acceptance criteria
- [ ] `SideObservation` exists with the value-implies-last-success invariant unit-tested.
- [ ] A successful scrape stamps each side's last-success time from the injected `Clock`
      (unit test with a fixed clock).
- [ ] The Valkey snapshot round-trips an app carrying per-side value + last-success (+ null
      last-failure) — integration test.
- [ ] `GET /version` returns each side as `{version, readAt}` with `readAt` an absolute
      instant — integration test.
- [ ] A row shows a relative "read Xm ago" under each version and the absolute-local time on
      hover — frontend test drives it from a fixed instant.
- [ ] `gradle test`, the integration suite, and `yarn test` pass.

## Blocked by
None — can start immediately.
