# [03] Separate, larger targeted-scrape budget

Status: done
Type: AFK

## Plan
See `../plan.md` and `../../../docs/adr/0006-*.md` (the separate-budget decision) plus
`../../../CONTEXT.md` (the **Scrape budget** term, now two budgets).

## What to build
Give targeted scrapes their own rolling-window budget so agent-driven update work cannot starve the
UI's full-Refresh budget. Today there is one `ScrapeRateLimiter` bean over the Valkey ZSET
`scrape:budget`, configured from `platform-config.scrape-trigger`.

- Generalise `ValkeyScrapeRateLimiter` so it is parameterised by `(key, window, maxPerWindow)`
  rather than hard-wiring `scrape:budget` and `config.scrapeTrigger()`. The atomic Lua window logic
  is unchanged.
- Add config: `ApplicationConfigLoader.targetedScrapeTrigger()` returning a `ScrapeTrigger`
  (defaults `max-per-window=30`, `window=1h`) — comfortably above the full-scrape default of 10/1h.
  Add the values to `application.yml` (`%dev`) and document the prod ConfigMap key.
- Produce two qualified `ScrapeRateLimiter` beans — full-scrape budget (key `scrape:budget`) and
  targeted-scrape budget (key `scrape:targeted:budget`) — and inject the targeted one into the
  `targetedScrape` path of `ApplicationVersionService`, leaving the full-scrape path on the existing
  budget.
- The targeted path now reports *its* budget's `triggersRemaining`/`windowResetsInSeconds`/
  `retryAfterSeconds`; the full path is unchanged.

After this slice the two budgets are fully independent: draining one leaves the other untouched.

## Architectural surface
- Use cases: TargetedScrape (rewired to its own budget)
- Ports: ScrapeRateLimiter (two instances, parameterised); ApplicationConfigLoader
  (+`targetedScrapeTrigger`)
- Adapters: ValkeyScrapeRateLimiter (parameterised + second key), CDI budget qualifiers/producer

## Acceptance criteria
- [ ] Two `ScrapeRateLimiter` beans exist over distinct Valkey keys (`scrape:budget`,
      `scrape:targeted:budget`).
- [ ] `platform-config.targeted-scrape-trigger` is read with defaults `max-per-window=30`,
      `window=1h`; `application.yml` dev values present.
- [ ] A targeted scrape spends only from the targeted budget; a full scrape (Refresh / trigger) spends
      only from the full-scrape budget — verified by draining one and confirming the other still
      admits (integration test against the two ZSETs).
- [ ] The targeted scrape's `ScrapeStatus` budget telemetry reflects the targeted budget's remaining
      slots and reset/retry timing.

## Blocked by
`01-targeted-scrape-http.md` (the targeted path it rewires).
