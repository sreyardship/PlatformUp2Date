# Plan: Backend-unavailable state for the frontend

## Problem
When the backend is down, the frontend fires a raw `alert("AxiosError: Network
Error")` and then renders a fully-styled board that lies: "Total Apps: 0" — 
indistinguishable from a genuinely empty fleet. The same lie shows briefly while
the first fetch is in flight. The UI must surface *Backend unavailable* (see
`CONTEXT.md`) honestly, in the existing dark-dashboard style.

Decisions settled in the grill session:
1. Full-body error state (TopBar stays) when no data has ever loaded.
2. Any failed `GET /version` triggers it; subtext distinguishes *unreachable*
   (no HTTP response) from *API error* (HTTP status shown).
3. If data has already been shown, a failed refresh keeps the board and shows a
   dismissible banner instead — consistent with the Side-freshness philosophy
   (last-known value + failure marker).
4. Manual Retry only; no auto-retry loop (matches the app's no-polling design).
5. Initial load shows a centered spinner, not fake zeros.

## Constraints
- Language/framework: React 18 function components, MUI (dark theme in
  `src/theme.js`), Vite. Plain JSX, no TypeScript.
- Test framework: Vitest + React Testing Library (`vi.mock`, `*.test.jsx`
  colocated with components). CLAUDE.md's "CRA + Jest" note is stale.
- Existing patterns: `App` owns data fetching and passes props down;
  `Dashboard` is the layout container; API calls go through
  `src/api/axiosClient.js` (shared axios instance with interceptors) and
  `src/api/versionClient.js`; card style is `bgcolor: 'background.paper'`,
  `border: '1px solid #282c31'`, `borderRadius: 2`; TopBar already uses MUI
  `Snackbar`/`Alert` for transient messages; error objects thrown to callers
  are `err.response` (so `err.status`/`err.data`, per TopBar's 429 handling).

## Domain Model

This is a frontend-only feature; the "domain" is the fetch lifecycle.

### Value Objects
- **FetchPhase**: `'loading' | 'loaded' | 'error'` — the state of the version
  snapshot on this surface. Never had data + in flight = `loading`; never had
  data + failed = `error`; data received at least once = `loaded` forever
  (later failures set a refresh-failure flag, they do not regress the phase).
- **FailureKind**: derived from the rejected error — *unreachable* (no
  `status` on the error, i.e. no HTTP response) vs *API error* (carries an
  HTTP status). Pure function of the error object; keep it a small helper so
  the error card and the banner share one interpretation.

### Use Cases
- **LoadVersions**: fetch the snapshot; drive FetchPhase; on later refreshes,
  record failure without discarding the last loaded snapshot.
- **RetryLoad**: re-run LoadVersions from the error state (wired to the Retry
  button; same function, no special casing).

## Ports

### VersionGateway (existing: `versionClient`)
- Purpose: read the version snapshot from the backend.
- Operations: `getVersions() -> Promise<snapshot>` — must **reject** on any
  failure (network or HTTP), never resolve `undefined`.
- Types: snapshot object keyed by app name (unchanged).

## Adapters

### Driven Adapters
- **axiosClient** fulfills VersionGateway's transport: the response
  interceptor drops `alert()` and rejects network errors with the original
  axios error (no `.status`), continuing to throw `err.response` for HTTP
  errors. Side benefit: TopBar's and RescrapeButton's existing `catch` blocks
  — currently dead on network errors — start firing.

### Driving Adapters
- **App** (existing): owns FetchPhase + refresh-failure state alongside
  `versionData`; passes them to Dashboard.
- **Dashboard** (existing): branches the body — spinner (`loading`), error
  card (`error`), board + optional banner (`loaded`).
- **BackendUnavailable** (new component): full-body error card — warning icon
  in `error.main`, "Backend unavailable" headline, FailureKind subtext, Retry
  button. Styled like SummaryCards' cards.

## Test Strategy

### Unit Tests
- FailureKind helper: no-status error → unreachable wording; status-carrying
  error → "API error (500)"-style wording.
- axiosClient interceptor: network error rejects (no alert), HTTP error
  rejects with `err.response` (existing versionClient tests adjust).

### Integration Tests (component, Vitest + RTL, mocked versionClient)
- App: first fetch rejects → error card visible, no "Total Apps" text; Retry
  click re-calls `getVersions`, success replaces card with board.
- App: first fetch pending → spinner, no fake zeros; resolve → board.
- App: first fetch succeeds, `onRefreshed` fetch rejects → board still shows
  previous data + banner; dismiss hides it; next successful fetch clears it.

### System Tests
Not applicable — single-page app, component tests through `App` already cover
the composed flows.

## Composition Root
`src/index.jsx` (unchanged) renders `App`, which is the composition point:
it wires `versionClient` to the fetch lifecycle and hands phase + data +
retry callback to `Dashboard`.

## File Structure
```
frontend/src/
├── api/axiosClient.js        # modified: reject, don't alert
├── App.jsx                    # modified: FetchPhase + refresh-failure state
├── Dashboard.jsx              # modified: body branching
├── BackendUnavailable.jsx     # new: full-body error card
├── BackendUnavailable.test.jsx
├── failureKind.js             # new: error → kind/subtext helper (framework-free)
└── failureKind.test.js
```
(Banner in slice 03 may live inline in Dashboard or as a small component —
implementer's call; reuse MUI Alert like TopBar does.)

## Slices
1. **01-backend-unavailable-error-state** — interceptor rejects honestly; first-load
   failure shows the full-body error card with diagnostic subtext and a working
   Retry. Blocked by: none.
2. **02-initial-load-spinner** — in-flight first load shows a centered spinner
   instead of fake zeros. Blocked by: 01.
3. **03-failed-refresh-banner** — failed refresh keeps the board and shows a
   dismissible banner that clears on the next success. Blocked by: 01.
