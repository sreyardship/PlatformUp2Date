# [03] Summary cards

Status: done
Type: AFK

## Plan
See `../plan.md` for the shared architecture (domain, ports, adapters, test strategy).

## What to build
The three bento summary cards beneath the top bar, derived entirely client-side from the
version payload (which now carries `drift` after slice 01):

- **Total Apps** — count of applications.
- **Up to Date** — count where `drift === 'NONE'` (green accent + check icon).
- **Updates Available** — a 3-way breakdown showing PATCH · MINOR · MAJOR counts, each with
  its severity colour and dot (amber / orange / red), matching `screen.png`.

Cards use the level-1 surface, 1px outline, soft 8px radius, and the label-style uppercase
headers from `design.md`. Counts recompute when version data refreshes. Put the count
derivation in a small reusable helper (`drift.js`) so the table/sort slices can share it.

## Architectural surface
- Use cases: Get application versions (presentation only)
- Ports: none (consumes data already fetched by `App`)
- Adapters: `SummaryCards` component + `drift.js` helpers

## Acceptance criteria
- [ ] Three cards render: Total Apps, Up to Date, Updates Available.
- [ ] Up to Date counts only `drift === 'NONE'`; Updates Available splits into PATCH/MINOR/MAJOR counts.
- [ ] Counts are correct for a mixed payload and update on refresh.
- [ ] Severity colours/dots match the design (patch amber, minor orange, major red).
- [ ] A test asserts the counts for a representative mixed payload.

## Blocked by
`01-drift-on-version-api.md` (needs `drift` in the payload) and `02-themed-app-shell.md`
(needs the theme + shell to render into).

## User stories covered
N/A — no PRD.
</content>
