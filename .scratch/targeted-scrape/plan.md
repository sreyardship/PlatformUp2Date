# Plan: Targeted scrapes — refresh a chosen app/side without re-hitting every upstream

## Problem
A full scrape re-fetches every monitored app's current and latest version — every deployed
app plus the rate-limited GitHub Releases API for every repo — just to learn one fact. When an
agent (or a human) is upgrading a single app and wants to confirm its deployed version moved, that
whole-fleet pass is wasteful load on upstream servers, especially GitHub. We add a **targeted
scrape**: a call names a set of *scrape targets* (`(app, side)` pairs) and fetches only those
sources, merging the result into the shared snapshot.

See `../../CONTEXT.md` for the domain glossary (Scrape, Full scrape, Targeted scrape, Scrape target,
Target result, Outcome, Scrape budget) and `../../docs/adr/0006-targeted-scrape-merges-without-touching-the-fleet-clock.md`
for the two headline decisions: the targeted merge leaves the fleet staleness clock untouched, and
targeted scrapes draw on their own separate budget.

## Constraints
- Language/framework: Java 21, Quarkus 3.10.1, hexagonal (ports & adapters). Build with Gradle.
- Test framework: JUnit 5; REST-assured for HTTP adapters; plain fakes (no CDI container) for core
  unit tests, mirroring the existing `ApplicationVersionService` / `VersionSourceResolver` test style.
- Existing patterns to honour:
  - The scrape loop and orchestration live in `ApplicationVersionService` (the core), not in
    adapters. Adapters are thin translation only (ADR-0005).
  - Shared scrape state is a single Valkey key (`scrape:snapshot`) with whole-value overwrite;
    every mutation serialises through the one global `scrape:lock` (ADR-0003). Stores/limiters fail
    closed.
  - The manual-scrape HTTP surface (`ScrapeController`) is hand-written and deliberately NOT in the
    OpenAPI spec; the new targeted endpoint follows suit.
  - `Clock` is injected into the service for deterministic staleness tests.
  - Native image: any record (de)serialised by Jackson or returned over MCP needs
    `@RegisterForReflection`.

## Domain Model

### Value Objects
- **Side**: enum `CURRENT | LATEST | BOTH` — which half (or both) of an Application to read.
- **ScrapeTarget**: `(name: String, side: Side)`. One unit of a targeted scrape. Immutable;
  validates non-null/non-blank name and non-null side. Equality by value.
- **TargetResult**: `(name: String, side: Side, succeeded: boolean, reason: String)`. The per-app
  fate within a scrape that ran. `reason` is null/empty on success, a short cause on failure. `side`
  records what was actually read (a cold-start single-side target that fell back reads `BOTH`).
  Shared by both the full and targeted scrape paths.

### Entities
- None new. `VersionApplication` (name + current `Version` + latest `Version`) is unchanged; a
  targeted merge produces new `VersionApplication` instances spliced into the snapshot list.

### Use Cases
- **TargetedScrape** (new): given a list of `ScrapeTarget`, acquire the global lock (lose →
  `IN_PROGRESS`), spend a targeted-budget slot (exhausted → release lock, `RATE_LIMITED`), read the
  current snapshot, for each target resolve its sources and read the requested side(s), field-level
  splice the result over the matching app, write the merged snapshot back **re-supplying the existing
  `lastAttemptAt`** (frozen fleet clock), release the lock, and return `SCRAPED` with per-target
  `TargetResult`s and budget telemetry. Lives on `ApplicationVersionPort` alongside the existing
  use cases.
- **TriggerScrape** (existing, extended): the full scrape now also emits per-app `TargetResult`s
  (which apps failed and why), not just `attempted`/`failed` counts.
- **GetApplications** (existing, unchanged): still serves/refreshes the whole-fleet snapshot.

### Domain Services
- None new. Merge logic is part of the `TargetedScrape` use case in `ApplicationVersionService`.

## Ports

### ApplicationVersionPort (in) — extended
- Purpose: the inbound use-case surface the driving adapters call.
- New operation: `ScrapeStatus targetedScrape(List<ScrapeTarget> targets)`.
- Existing: `List<VersionApplication> getApplications()`, `ScrapeStatus triggerScrape()`.
- Types in/out: domain `ScrapeTarget`, `ScrapeStatus` (now carrying `List<TargetResult>`).

### ScrapeRateLimiter (out) — two instances
- Purpose: a cluster-wide rolling-window budget. Already exists; today a single bean over key
  `scrape:budget` reading `config.scrapeTrigger()`.
- Change: generalise so two independent budgets coexist — the existing **full-scrape budget** and a
  new, larger **targeted-scrape budget** (own Valkey key, own config), selected by the service per
  path. Operations unchanged: `Decision tryAcquire(Instant now)`, `Budget peek(Instant now)`.

### ScrapeStateStore (out) — unchanged
- The targeted merge is a read-modify-write composed in the service: `read()` → splice →
  `write(mergedApplications, existingLastAttemptAt)`. No new port method; the existing
  whole-value `write` carries the preserved (frozen) `lastAttemptAt`.

### ScrapeLock (out) — unchanged
- The single global `scrape:lock` serialises targeted and full mutations alike. Targeted is
  lock-first: lose → `IN_PROGRESS`.

### VersionSources / ApplicationSources (out) — unchanged
- The service indexes `applicationSources()` by name to resolve a target's `current()`/`latest()`
  capability and read one side. A name not present → that target fails with `"not monitored"`.

## Adapters

### Driven Adapters (domain calls out)
- **ValkeyScrapeRateLimiter** implements **ScrapeRateLimiter**: parameterised by `(key, window,
  maxPerWindow)` so it can back both budgets. Targeted budget uses key `scrape:targeted:budget` and
  config `platform-config.targeted-scrape-trigger` (default 30 per 1h). Wired as two qualified CDI
  beans (`@FullScrapeBudget` / `@TargetedScrapeBudget`) or via a small producer.
- **ValkeyScrapeStateStore** implements **ScrapeStateStore**: unchanged (whole-value overwrite).
- **VersionSourceResolver** implements **VersionSources**: unchanged.

### Driving Adapters (outside calls in)
- **Targeted scrape HTTP endpoint** — `POST /api/v1/scrape/applications`, hand-written JAX-RS in the
  `ScrapeController` style (request DTO `{ targets: [{ name, side }] }`). Maps the `ScrapeStatus`:
  `SCRAPED`/`IN_PROGRESS` → 200; `RATE_LIMITED` → 429 + `Retry-After`; Valkey unreachable → 503 via
  the existing `ScrapeStateUnavailableExceptionMapper`. Body carries the per-target results.
- **`scrape_applications` MCP tool** — in `ApplicationMcpTools` (or a sibling), `@Tool` taking a
  `targets` arg, returning `ScrapeStatus`. Telemetry-only by design (the load saved is upstream; a
  follow-up `get_application` reads the snapshot, never GitHub). DTOs `@RegisterForReflection`.
- **`trigger_scrape` MCP tool + `POST /api/v1/scrape`** (existing) — bodies gain the per-target
  results from the extended full scrape.
- **Web UI per-app buttons** (React/MUI) — each `Version` card gains "Update current" / "Update
  latest" buttons calling `POST /api/v1/scrape/applications` for a single `(app, side)` target via a
  new `versionClient.scrapeApplication(name, side)`, then re-pulling the list. Reuses the existing
  `RefreshButton` busy + 429-cooldown interaction model, per button.

## Test Strategy

### Unit Tests (core, plain fakes + injected Clock)
- TargetedScrape merge: a `current`-only target splices the new current and keeps the snapshot's
  existing latest (and vice-versa); `both` replaces both; the merged write re-supplies the existing
  `lastAttemptAt` (fleet clock NOT advanced).
- Cold-start: a single-side target for an app absent from the snapshot falls back to reading BOTH and
  writes a complete entry; its `TargetResult.side == BOTH`. With an entirely empty snapshot the
  written `lastAttemptAt` is a definitely-stale instant so the next read still triggers a full scrape.
- Unknown app → `TargetResult` `succeeded=false`, reason `"not monitored"`, rest of batch proceeds.
- Per-target failure isolation: one source throwing fails only its target, others succeed.
- Lock lost → `IN_PROGRESS`, no budget spent, no write. Budget exhausted → `RATE_LIMITED`, lock
  released, no write.
- Full scrape (slice 02): each app yields a `TargetResult`; failures carry a reason; counts still
  satisfy `appsSucceeded == appsAttempted - appsFailed`.

### Integration Tests (adapters)
- HTTP `POST /api/v1/scrape/applications` (REST-assured): outcome→status-code mapping, `Retry-After`
  on 429, body shape including per-target results, malformed body handling.
- MCP `scrape_applications`: returns the expected `ScrapeStatus` shape with target results.
- ValkeyScrapeRateLimiter second budget: the targeted key is independent of the full-scrape key —
  draining one does not affect the other (separate ZSETs).

### Frontend Tests (Jest/RTL)
- `Version` per-app buttons: render, click calls `scrapeApplication(name, side)` with the right
  side, success triggers the refresh callback, a 429 starts the per-button cooldown — mirroring the
  existing `RefreshButton.test`.

### System Tests
- Covered by the adapter/frontend tests above; the app has these thin entry points and the shared
  core, so no separate end-to-end layer is added.

## Composition Root
- `ApplicationVersionService` (core, `@ApplicationScoped`) gains a constructor dependency on the
  targeted-scrape rate limiter alongside the existing one (qualified injection), and the new
  `targetedScrape` method. The test-visible constructor keeps taking an explicit `Clock`.
- `ApplicationConfigLoader` (`@ConfigMapping(prefix="platform-config")`) gains
  `targetedScrapeTrigger()` returning a `ScrapeTrigger` (defaults `max-per-window=30`, `window=1h`).
  Dev/prod values added to `application.yml` / the mounted ConfigMap.
- Two `ScrapeRateLimiter` beans produced with their respective `(key, config)`; the service selects
  per path.
- Driving adapters (`ScrapeController` targeted method, `ApplicationMcpTools` tool) are CDI beans
  invoking the port; no wiring beyond construction.

## File Structure
Fits the existing `org.yardship` layout:
```
core/domain/primitives/      Side, ScrapeTarget, TargetResult (new)
core/ports/in/               ApplicationVersionPort (+targetedScrape), ScrapeStatus (+targetResults)
core/ports/out/              ScrapeResult (+targetResults)
core/services/               ApplicationVersionService (+targetedScrape, +full-scrape results)
adapters/in/                 ScrapeController (+targeted endpoint, +request DTO)
adapters/in/mcp/             ApplicationMcpTools (+scrape_applications)
adapters/out/valkey/         ValkeyScrapeRateLimiter (parameterised), budget qualifiers/producer
adapters/out/versionclient/  ApplicationConfigLoader (+targetedScrapeTrigger)
resources/                   application.yml (+targeted-scrape-trigger)
```
Frontend (`frontend/src`):
```
api/versionClient.js         +scrapeApplication(name, side)
Version.jsx                  +Update current / Update latest buttons (busy + 429 cooldown)
VersionList.jsx              thread onRefreshed down to each Version
```

## Slices
The files in `issues/` are the source of truth; this is the map.

1. **01-targeted-scrape-http** — targeted scrape end-to-end over HTTP, reusing the existing budget;
   establishes `Side`/`ScrapeTarget`/`TargetResult`, the port method, and the merge/lock/frozen-clock
   composition. Blocked by: none (tracer bullet).
2. **02-full-scrape-failure-reporting** — the existing full scrape reports which apps failed via the
   shared `TargetResult`, on both its surfaces. Blocked by: 01.
3. **03-separate-targeted-budget** — a separate, larger targeted-scrape budget (own Valkey key +
   config), wired to the targeted path. Blocked by: 01.
4. **04-scrape-applications-mcp-tool** — the agent-facing `scrape_applications` MCP tool over the
   same port method. Blocked by: 01.
5. **05-frontend-per-app-update-buttons** — web-UI "Update current / Update latest" buttons on each
   application card, calling the targeted endpoint for one `(app, side)`. Blocked by: 01.
