# [05] Filter & sort

Status: done
Type: AFK

## Plan
See `../plan.md` for the shared architecture (domain, ports, adapters, test strategy).

## What to build
Make the application table interactive, matching the reference's table controls (minus
pagination, which is deliberately dropped given the small fleet):

- **Filter** — a search input in the "Application Status" card header that live-filters
  rows by application name (case-insensitive, client-side).
- **Sortable headers** — clicking a column header sorts the rows; clicking again toggles
  direction, with a sort indicator on the active column:
  - App Name — alphabetical.
  - Status — by drift **severity** using the domain order `NONE < PATCH < MINOR < MAJOR`
    (reuse the severity helper from `drift.js`).
  - Current / Latest Version — **semver-aware**, not lexicographic.
- **Default sort** — most-outdated-first (highest drift severity at the top) on initial
  render.
- **Footer** — a "N applications" count reflecting the filtered set. No prev/next controls.

## Architectural surface
- Use cases: Get application versions (presentation)
- Ports: none
- Adapters: `ApplicationTable` (filter/sort state) + `drift.js` (severity order, semver compare)

## Acceptance criteria
- [ ] Typing in the filter narrows visible rows by name; clearing restores all.
- [ ] Clicking a header sorts by that column; clicking again reverses direction.
- [ ] Status sorts by severity order (NONE < PATCH < MINOR < MAJOR), not alphabetically.
- [ ] Version columns sort semver-aware (e.g. v2.9.0 < v2.10.0).
- [ ] Initial render is sorted most-outdated-first.
- [ ] The footer shows the count of currently visible (filtered) applications; no pagination controls exist.
- [ ] Tests cover name filtering, severity sort order, and semver version sort.

## Blocked by
`04-application-table.md` (operates on the rendered table).

## User stories covered
N/A — no PRD.
</content>
