# [03] Failed-refresh banner over the last loaded board

Status: done
Type: AFK

## Plan
See `../plan.md` for the shared architecture (domain, ports, adapters, test strategy).

## What to build
When a refresh (`onRefreshed` → `getVersions`) fails *after* the board has
already shown data, the board keeps displaying the last loaded snapshot and a
dismissible error banner appears above the summary cards: "Backend unavailable
— showing last loaded data", with the slice-01 failure-kind wording
(unreachable vs API error with status) folded into the message. The banner
stays until the user dismisses it or a subsequent fetch succeeds, whichever
comes first — unavailability is a persistent condition, so no auto-hide
timeout. Reuse MUI `Alert` (severity `error`) as TopBar's snackbar already
does; a persistent inline banner, not a toast.

This mirrors the Side-freshness philosophy from `CONTEXT.md`: keep the
last-known value visible, mark the failed refresh — never blank good data.

## Architectural surface
- Use cases: LoadVersions (post-load failure path)
- Ports: none new
- Adapters: App (refresh-failure flag), Dashboard (banner above SummaryCards),
  failureKind helper (reused from slice 01)

## Acceptance criteria
- [ ] Load succeeds, next refresh rejects: board still shows the previous data (rows, counts unchanged) and the banner is visible above the summary cards.
- [ ] Banner text names the failure kind (unreachable vs HTTP status) and says last loaded data is being shown.
- [ ] Banner is dismissible; it does not auto-hide.
- [ ] A later successful fetch clears the banner (even if not dismissed) and updates the board.
- [ ] The full-body error card from slice 01 never appears once data has loaded.
- [ ] Component tests cover: refresh failure → data retained + banner; dismiss → hidden; subsequent success → banner gone.

## Blocked by
`01-backend-unavailable-error-state.md` (rejecting client, failure-kind helper, phase state).
