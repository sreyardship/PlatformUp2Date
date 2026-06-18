# [01] Targeted scrape end-to-end over HTTP (tracer bullet)

Status: done
Type: AFK

## Plan
See `../plan.md` for the shared architecture (domain, ports, adapters, test strategy) and
`../../../CONTEXT.md` + `../../../docs/adr/0006-*.md` for the glossary and the headline decisions.

## What to build
A new HTTP endpoint `POST /api/v1/scrape/applications` that refreshes a chosen set of
`(app, side)` targets instead of the whole fleet — proving the targeted-scrape architecture composes
end-to-end before we add breadth.

Request body: `{ "targets": [ { "name": "argo-cd", "side": "current" }, ... ] }`, where `side` is
one of `current`, `latest`, `both`.

Behaviour, all orchestrated in the core (`ApplicationVersionService.targetedScrape`):
- **Lock-first**: acquire the existing global `scrape:lock`; if lost, return `IN_PROGRESS` (no
  budget spent, no write).
- **Budget**: spend one slot. For THIS slice, reuse the existing scrape budget (the separate
  targeted budget arrives in slice 03). If exhausted, release the lock and return `RATE_LIMITED`
  with `retryAfterSeconds`.
- **Merge**: read the current snapshot; for each target, look up its `ApplicationSources` by name
  and read the requested side(s); field-level splice the result over the matching app — a
  `current`-only target keeps the snapshot's existing `latest` (and vice-versa), `both` replaces
  both. Write the merged snapshot back **re-supplying the existing `lastAttemptAt`** so the
  fleet-wide staleness clock is NOT advanced.
- **Cold-start**: a single-side target for an app not yet in the snapshot falls back to reading BOTH
  sides (you can't write half a `VersionApplication`); its `TargetResult.side` records `BOTH`. If
  the snapshot is entirely empty, the written `lastAttemptAt` must be a definitely-stale instant so
  the next plain read still triggers a full scrape.
- **Unknown app**: a target naming an app not in the configured sources fails on its own —
  `TargetResult{ succeeded=false, reason="not monitored" }` — without sinking the rest of the batch.
- **Per-target isolation**: one source throwing fails only that target; others still land.
- **Response (telemetry only)**: `SCRAPED` with the list of per-target `TargetResult`s and budget
  telemetry. No version data inline — a caller reads `GET /api/v1/version` (or later
  `get_application`) to see refreshed values, which hits the snapshot, not upstreams.

HTTP mapping (hand-written JAX-RS in the `ScrapeController` style, NOT in the OpenAPI spec):
`SCRAPED`/`IN_PROGRESS` → 200 with the `ScrapeStatus` body; `RATE_LIMITED` → 429 + `Retry-After`;
Valkey unreachable → 503 via the existing `ScrapeStateUnavailableExceptionMapper`.

This slice introduces the shared domain types `Side`, `ScrapeTarget`, `TargetResult`, the port
method `ApplicationVersionPort.targetedScrape(List<ScrapeTarget>)`, and adds `List<TargetResult>` to
`ScrapeStatus` (populated for the targeted path here; the full path adopts it in slice 02).

## Architectural surface
- Use cases: TargetedScrape (new)
- Ports: ApplicationVersionPort (+`targetedScrape`); reuses ScrapeStateStore, ScrapeLock,
  ScrapeRateLimiter, VersionSources (all unchanged this slice)
- Adapters: targeted HTTP endpoint on `ScrapeController` (+request DTO)

## Acceptance criteria
- [ ] `POST /api/v1/scrape/applications` with a valid targets body returns 200 and a `SCRAPED`
      `ScrapeStatus` carrying one `TargetResult` per target.
- [ ] A `current`-only target updates the app's current version in the snapshot and leaves its latest
      unchanged; `both` updates both; `latest`-only is symmetric.
- [ ] The snapshot's `lastAttemptAt` is unchanged by a targeted scrape over an existing snapshot
      (verified via the injected `Clock` in a unit test).
- [ ] A single-side target for an app absent from the snapshot reads BOTH sides, writes a complete
      entry, and reports `TargetResult.side == BOTH`.
- [ ] A target naming an unmonitored app yields `succeeded=false, reason="not monitored"` while other
      targets in the same call still succeed.
- [ ] Lock lost → `IN_PROGRESS`, no slot spent, no snapshot write. Budget exhausted → `RATE_LIMITED`
      + `retryAfterSeconds`, lock released, no write.
- [ ] One target's source throwing isolates to that target's `TargetResult`; the batch otherwise
      succeeds.
- [ ] Outcome→status mapping verified (200 / 429+`Retry-After` / 503) via REST-assured.

## Blocked by
None — can start immediately (tracer bullet).
