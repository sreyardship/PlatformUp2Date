# [04] `scrape_applications` MCP tool

Status: done
Type: AFK

## Plan
See `../plan.md` and `../../../CONTEXT.md` (Targeted scrape, Scrape target). ADR-0006 explains why
this is a *new* tool rather than an argument on `trigger_scrape`.

## What to build
The agent-facing driving adapter for targeted scrapes: a new MCP tool `scrape_applications` over the
existing `ApplicationVersionPort.targetedScrape` use case (built in slice 01). A second thin entry
point — no new core logic.

- `@Tool(name = "scrape_applications", ...)` in `ApplicationMcpTools` (or a sibling adapter), taking
  a `targets` argument: a list of `(name, side)` with `side` one of `current`, `latest`, `both`.
- Returns the `ScrapeStatus` (outcome + per-target `TargetResult`s + budget telemetry). Telemetry
  only: the tool description must steer the agent to read `get_application` / `GET /api/v1/version`
  afterwards to see refreshed values (the snapshot read costs nothing upstream).
- Write a description that explains the load motivation (refresh one app's side without re-hitting
  every upstream), that it is rate-limited by the **targeted** budget (separate from
  `trigger_scrape`), and how to read the `outcome` and per-target results.
- Any request/response DTOs the MCP layer (de)serialises need `@RegisterForReflection` for the
  native image.

If slice 03 has landed, the tool naturally reports the separate targeted budget; if not, it reports
whatever budget the targeted path currently uses. No code change either way.

## Architectural surface
- Use cases: TargetedScrape (existing — reused)
- Ports: ApplicationVersionPort.targetedScrape
- Adapters: `scrape_applications` MCP tool

## Acceptance criteria
- [ ] `scrape_applications` appears as an MCP tool and accepts a `targets` list of `(name, side)`.
- [ ] Calling it runs a targeted scrape and returns a `ScrapeStatus` with per-target `TargetResult`s
      and budget telemetry (verified by an MCP-shape test).
- [ ] Mixed sides in one call work (e.g. `{argo-cd, current}` + `{git-tea, latest}`).
- [ ] The tool description documents telemetry-only behaviour, the follow-up read, and the targeted
      rate limit.
- [ ] DTOs are registered for reflection (native-image safe).

## Blocked by
`01-targeted-scrape-http.md` (the port method and domain types). Pairs naturally with
`03-separate-targeted-budget.md` but does not require it.
