# [05] Frontend: per-app "update current / update latest" buttons

Status: done
Type: AFK

## Plan
See `../plan.md` and `../../../CONTEXT.md` (the web UI is a **Surface**; these buttons request a
**targeted scrape**). React (Create React App + MUI), tests in Jest/RTL — mirror the existing
`RefreshButton` patterns.

## What to build
On each application card (`Version`) add two buttons — **Update current** and **Update latest** —
that trigger a targeted scrape for just that app/side, then refresh the list so the card reflects the
new value. This is the web-UI driving adapter over the targeted-scrape endpoint from slice 01.

- API client (`src/api/versionClient.js`): add `scrapeApplication(name, side)` →
  `POST /api/v1/scrape/applications` with body `{ targets: [{ name, side }] }`.
- `Version` gains two buttons wired to `scrapeApplication(name, 'current')` and
  `(name, 'latest')`. On success, call an `onRefreshed` callback (threaded down from `App` →
  `Display` → `VersionList` → `Version`, the same callback `RefreshButton` already uses) to re-pull
  versions.
- Reuse the `RefreshButton` interaction model per button: disable + show busy while in flight; on a
  429 response start a per-button cooldown from `retryAfterSeconds` ("Retry in Ns"). Because targeted
  scrapes use their own (larger) budget once slice 03 lands, this cooldown is independent of the
  global Refresh button's.
- Keep the load intent visible: these update one side of one app, not the whole fleet.

Each button maps to exactly one `(app, side)` target — no multi-target UI in this slice (the batch
capability stays an API/MCP concern for now).

## Architectural surface
- Use cases: TargetedScrape (existing) via the HTTP endpoint from slice 01
- Ports/Adapters (frontend): `versionClient.scrapeApplication`; `Version` component buttons;
  `onRefreshed` threading through `VersionList`

## Acceptance criteria
- [ ] Each application card shows an "Update current" and an "Update latest" button.
- [ ] Clicking a button POSTs to `/scrape/applications` with a single `(name, side)` target and, on
      success, refreshes the list so the card shows the updated value.
- [ ] A button is disabled while its request is in flight.
- [ ] A 429 response puts that button into a per-button cooldown driven by `retryAfterSeconds`.
- [ ] Jest/RTL tests cover: button renders, click calls the client with the right `(name, side)`,
      success triggers refresh, 429 starts cooldown — following the existing `RefreshButton.test`
      style.

## Blocked by
`01-targeted-scrape-http.md` (the `POST /api/v1/scrape/applications` endpoint). Independent of the
MCP tool (04); benefits from but does not require the separate budget (03).
