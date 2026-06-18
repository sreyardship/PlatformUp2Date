# [02] Full scrape reports which apps failed

Status: done
Type: AFK

## Plan
See `../plan.md` for the shared architecture and `../../../CONTEXT.md` (the **Target result** term).

## What to build
Make the existing full scrape report *which* apps failed and why — not just an aggregate count —
reusing the `TargetResult` shape introduced in slice 01.

Today `ApplicationVersionService.scrape()` catches per-app failures, logs them, and only increments
a `failed` counter; `ScrapeResult` carries `attempted`/`failed` ints and nothing about identity.
Thread per-app results through so a caller learns the named casualties:
- The scrape loop produces one `TargetResult` per configured app: `succeeded=true` for resolved
  apps, `succeeded=false` with a short `reason` for the ones whose source threw. A full-scrape result
  uses `side = BOTH` (the loop reads both sides per app in one try — app-level granularity, by the
  ADR-0006 decision).
- `ScrapeResult` (out-port) carries `List<TargetResult>`; the existing `attempted`/`failed` counts
  remain consistent (`appsSucceeded == appsAttempted - appsFailed`).
- `ScrapeStatus.scraped(...)` carries the per-app `TargetResult`s through to both existing surfaces:
  the `trigger_scrape` MCP tool body and the `POST /api/v1/scrape` body.

No behaviour change to scraping itself — only the telemetry gains identity. The per-app failure
isolation (catch, count, continue) is unchanged.

## Architectural surface
- Use cases: TriggerScrape (existing, extended); the `scrape()` loop in `ApplicationVersionService`
- Ports: ScrapeResult (+`List<TargetResult>`), ScrapeStatus (already extended in slice 01)
- Adapters: `trigger_scrape` MCP tool body, `POST /api/v1/scrape` body (both already serialise
  `ScrapeStatus` — they inherit the new field)

## Acceptance criteria
- [ ] A full scrape with a failing app returns a `ScrapeStatus` whose `targetResults` names that app
      with `succeeded=false` and a non-empty `reason`, and the succeeding apps with `succeeded=true`.
- [ ] `appsAttempted`/`appsSucceeded`/`appsFailed` still hold and agree with the `targetResults`
      list.
- [ ] Each full-scrape `TargetResult` reports `side == BOTH`.
- [ ] The `POST /api/v1/scrape` response body and the `trigger_scrape` MCP tool result both expose
      the per-app results (verified via REST-assured / an MCP-shape test).
- [ ] Existing full-scrape tests updated; no change to scrape success/failure behaviour.

## Blocked by
`01-targeted-scrape-http.md` (shares the `TargetResult` type and the `ScrapeStatus.targetResults`
field).
