# [04] Application table

Status: done
Type: HITL

## Plan
See `../plan.md` for the shared architecture (domain, ports, adapters, test strategy).

## What to build
Replace the card-per-app list with the dashboard data table, inside an "Application Status"
card matching `screen.png`. One row per application with columns:

- **App Name** — avatar initial + name.
- **Status** — a drift badge: `NONE` → "Up to Date" (green dot), `PATCH` → "Patch
  Available" (amber), `MINOR` → "Minor Available" (orange), `MAJOR` → "Major Available"
  (red). The whole row gets a faint tint of the status colour, as in the reference.
- **Current Version** / **Latest Version** — version strings; latest is emphasised
  (primary colour) when the app is outdated.
- **Changelog** — an inert document-icon control retained for visual fidelity, with a
  tooltip such as "Changelog — coming soon". It performs no action.
- **Actions** — "Rescrape current" and "Rescrape latest" buttons on **every** row, each
  calling `versionClient.scrapeApplication(name, side)`. Reuse the existing per-button
  cooldown: on HTTP 429 the button disables and shows a "Retry in Ns" countdown. After a
  successful rescrape, version data refetches.

Sorting, filtering, and the footer count are out of scope here (slice 05) — render all
rows in payload order for now.

## Architectural surface
- Use cases: Get application versions (presentation), Targeted scrape
- Ports: `versionClient` (`scrapeApplication`)
- Adapters: `ApplicationTable`, `ApplicationRow`, `RescrapeButton` components

## Acceptance criteria
- [ ] One row renders per application with name, status badge, current, latest.
- [ ] Status badge and row tint reflect `drift` (NONE/PATCH/MINOR/MAJOR) with the design colours.
- [ ] Latest version is visually emphasised when the app is outdated.
- [ ] Each row shows "Rescrape current" and "Rescrape latest"; clicking calls `scrapeApplication(name, side)` and refetches on success.
- [ ] A row button on 429 disables and shows a "Retry in Ns" countdown that clears at 0.
- [ ] The Changelog control is present, inert, and has an explanatory tooltip.
- [ ] Tests cover badge-per-drift, the rescrape call per side, and the 429 countdown.
- [ ] Obsolete card-list tests (`Version`, `VersionList`) are updated/replaced.
- [ ] Visual check against `screen.png` for the table (HITL).

## Blocked by
`01-drift-on-version-api.md` (needs `drift`) and `02-themed-app-shell.md` (needs theme + shell).

## User stories covered
N/A — no PRD.
</content>
