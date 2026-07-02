# [01] Backend-unavailable error state on first load

Status: done
Type: AFK

## Plan
See `../plan.md` for the shared architecture (domain, ports, adapters, test strategy).

## What to build
When the very first `GET /version` fails — for any reason — the dashboard body
(TopBar stays) shows a centered "Backend unavailable" card in the existing
dark-card style instead of a board full of fake zeros: warning icon in the
theme's error color, "Backend unavailable" headline, a diagnostic subtext that
distinguishes *unreachable* (no HTTP response: "Couldn't reach the
PlatformUp2Date API") from *API error* (response received: include the HTTP
status), and a Retry button that re-runs the fetch and, on success, replaces
the card with the normal board.

To make this possible the shared axios response interceptor must stop calling
`alert()` and stop resolving `undefined` on network errors: it rejects in all
failure cases (network errors with the original error, HTTP errors with
`err.response` as today, so TopBar's 429 handling keeps working). `App` tracks
the fetch phase (never-loaded vs loaded vs failed-before-first-load) and hands
it to `Dashboard`, which branches the body. Interpret the error into a failure
kind in one small framework-free helper so slice 03 can reuse it.

Behavior while the first fetch is merely *pending* is out of scope (slice 02);
failures after data has loaded are out of scope (slice 03) — in this slice a
post-load refresh failure must simply not blank the board (keep last data,
no banner yet).

## Architectural surface
- Use cases: LoadVersions, RetryLoad
- Ports: VersionGateway (`versionClient.getVersions` now rejects on failure)
- Adapters: axiosClient (interceptor), App (phase state), Dashboard (branch),
  BackendUnavailable (new), failureKind helper (new)

## Acceptance criteria
- [ ] Backend down + fresh page load: no browser `alert()`, no "Total Apps: 0" — the body shows the Backend unavailable card with the *unreachable* subtext and a Retry button.
- [ ] First fetch rejected with an HTTP error (e.g. 500): same card, subtext names the status.
- [ ] Clicking Retry re-calls `getVersions`; on success the card is replaced by the populated board.
- [ ] The card matches the dashboard style (paper background, `#282c31` border, existing typography) and keeps the TopBar visible.
- [ ] axiosClient rejects on network errors (no `alert`), still throws `err.response` for HTTP errors; TopBar/RescrapeButton catch paths unaffected (429 cooldown still works).
- [ ] A refresh failure after data has loaded does not blank the board or show the error card.
- [ ] Component tests cover: first-load network failure → card; first-load HTTP failure → status in subtext; Retry → board. Unit tests cover the failure-kind helper and the interceptor's reject behavior.

## Blocked by
None — can start immediately.
