# [02] Themed app shell + Refresh All

Status: done
Type: HITL

## Plan
See `../plan.md` for the shared architecture (domain, ports, adapters, test strategy).

## What to build
The frontend tracer bullet: establish the dark theme and the dashboard shell so the rest
of the UI composes against it. This proves the theme, data fetch, and refresh feedback all
wire together before investing in cards and tables.

- A MUI dark theme built from `design.md` tokens — palette (deep-slate surfaces, action
  blue primary, status greens/ambers/reds), Inter typography, soft radii (buttons/inputs
  ~4px, cards ~8px). Swap `@fontsource/roboto` for `@fontsource/inter`. Apply via
  `ThemeProvider` + `CssBaseline` at the composition root.
- A top app bar: the brand logo (copy `style-reference/logo.png` into `public/`) + the
  "PlatformUp2Date" wordmark in the primary colour on the left, and a **Refresh All**
  button on the right that triggers a full scrape (`versionClient.triggerScrape`).
- Refresh feedback: on success, raise a transient Snackbar with the outcome
  (e.g. "Scraped 3/4 · 9 left"); on HTTP 429, disable the button and show a
  "Retry in Ns" countdown (port the existing `RefreshButton` cooldown logic).
- The content area below the bar can be an empty/placeholder container for now —
  cards and table arrive in slices 03/04.

Keep the existing `App` data-fetch responsibility; reshape its rendered tree toward the new
`Dashboard`/`TopBar` components.

## Architectural surface
- Use cases: Full scrape
- Ports: `versionClient` (frontend → HTTP)
- Adapters: React tree (`index` → `ThemeProvider` → `App` → `Dashboard`/`TopBar`)

## Acceptance criteria
- [ ] App renders in the dark theme with Inter applied; `@fontsource/roboto` removed, `@fontsource/inter` added.
- [ ] Top bar shows the logo + "PlatformUp2Date" wordmark and a Refresh All button.
- [ ] Refresh All calls `triggerScrape`; on success an outcome Snackbar appears and version data is refetched.
- [ ] On 429 the button disables and shows a "Retry in Ns" countdown that clears when it reaches 0.
- [ ] Tests cover the success-Snackbar path and the 429-countdown path.
- [ ] Visual check against `screen.png` for the top bar (HITL).

## Blocked by
None — can start immediately (independent of slice 01).
</content>
