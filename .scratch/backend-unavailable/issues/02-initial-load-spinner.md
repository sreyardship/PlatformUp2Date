# [02] Centered spinner while the first load is in flight

Status: done
Type: AFK

## Plan
See `../plan.md` for the shared architecture (domain, ports, adapters, test strategy).

## What to build
While the very first `GET /version` is still pending, the dashboard body shows
a centered MUI `CircularProgress` instead of the fake-zeros board ("Total
Apps: 0" / "0 applications"). TopBar stays visible. When the fetch resolves,
the spinner is replaced by the board; when it rejects, by the Backend
unavailable card from slice 01. This extends the fetch-phase branching slice
01 added to `App`/`Dashboard` — no new state shape, just rendering the
until-now-unrendered pending phase honestly.

Only the *first* load spins: refreshes after data has loaded keep the board on
screen as today (the RescrapeButton/TopBar already have their own busy
indicators).

## Architectural surface
- Use cases: LoadVersions (pending phase)
- Ports: none new
- Adapters: App (phase already tracked), Dashboard (loading branch)

## Acceptance criteria
- [ ] While the first fetch is unresolved, the body shows a centered spinner; "Total Apps" and "0 applications" are not in the document.
- [ ] Fetch resolves → board renders; fetch rejects → slice 01's error card renders.
- [ ] Refreshes after the first successful load do not show the full-body spinner.
- [ ] Component test: pending fetch → spinner and no fake zeros; resolution → board.

## Blocked by
`01-backend-unavailable-error-state.md` (fetch-phase state in App/Dashboard).
