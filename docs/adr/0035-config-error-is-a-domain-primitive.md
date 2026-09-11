---
status: accepted
---

# A *Config error* is a domain primitive, and a *Surface* reads it through a driving port

`ConfigError` lives in `org.yardship.adapters.out.versionsource.configerror`, and its Javadoc
states the reason:

> A server adapter type, not a domain primitive (ADR-0005 keeps substrate vocabulary — `type`
> strings, config field names — out of `:backend:domain`).

That sentence is retired here. `ConfigError` and `ConfigErrorScope` become domain primitives, and
every *Surface* reads them through a driving port instead of reaching into the driven adapter that
produces them.

## ADR-0005 never blocked this

ADR-0005 keeps substrate vocabulary out of the core, and that rule stands. It simply does not reach
this type:

```java
public record TargetResult(String name, Side side, boolean succeeded, String reason)  // domain, today
public record ConfigError (String application, ConfigErrorScope scope, String reason)  // this ADR
```

The two are the same shape. Each names a thing, classifies it with a closed enum, and carries a
free-text `reason`. No `type` string, no JSON Pointer, no `ca-cert` path appears in either
*structure*. Substrate detail appears inside `reason`, as prose — exactly as it already does in
`TargetResult.reason`, which the domain has carried since ADR-0006. `ConfigErrorScope`'s four
values — `CURRENT`, `LATEST`, `APP`, `CHANGELOG` — are the *sides* and the *Changelog link*, which
`CONTEXT.md` already defines as ubiquitous language.

ADR-0005 proves where the type must *not* go. It never chose where it does go.

## ADR-0032 answered a module question, not a layer question

The placement came from ADR-0032, whose words were:

> a substrate-free rule becomes a `:backend:domain` value type both modules construct; a
> substrate-bound rule stays in the server

Read plainly, that is a choice between two Gradle modules, made when `conf-check` forced every
shared type across a module boundary. The hexagon offers three homes — `core.domain`, `core.ports`,
`adapters` — and the middle one was never discussed, because the module question did not ask about
it.

[ADR-0034](0034-conf-check-removed-the-backend-proves-the-config.md) deleted `conf-check`, and the
`:backend:domain` module is folded into the server. "Stays in the server" then distinguishes
nothing: everything is in the server.

## The violation was structural, not careless

ADR-0032 requires a *Config error* to reach **every** *Surface* — `configErrors` on the REST
payload, the board's red glyph, `list_misconfigured_applications`, and
`pu2d_config_error{application,scope}`. The only reader of those errors was `ConfigErrors`, a CDI
bean in `adapters.out`.

So all three *Surfaces* imported from `adapters.out`. A driving adapter reaching sideways into a
driven one, ten times over — the single largest deviation from the hexagon in the backend, and it
was forced by a placement nobody chose on purpose.

With the type in the domain and the reads behind ports, that edge is zero, and the layered ArchUnit
rules pass with no exemptions.

## The ports

Two terms, so two driving ports, rather than one port meaning two things:

```
core.ports.in    ConfigErrorPort     configErrorsFor(app), allConfigErrors(), unnamedAppCount()
core.ports.in    ChangelogLinkPort   changelogFor(app)
core.ports.out   ConfigErrors        the recorded Config errors
core.ports.out   ChangelogLinks      the per-app Changelog link template
```

`ChangelogLinkService` is a one-method passthrough. That is the accepted price of keeping *Config
error* and *Changelog link* separate in the code, as `CONTEXT.md` keeps them separate in the
language.

## Considered Options

- **Widen `ApplicationVersionPort`** — rejected. That port answers one question: what version is
  each *Application* running, and what is the latest. Configuration is an input to assembly, not an
  answer to that question. Adding these reads makes the port mean two things.
- **A `config` component beside `core` and `adapters`**, depended on by both adapter sides —
  rejected, though it fits the "configuration is an assembly concern" model well. It introduces a
  fourth top-level concept to avoid a service that mostly forwards, and
  `list_misconfigured_applications` shows the forwarding is a real query with a real audience. A
  thin use case is still a use case.
- **Keep the type in `adapters.out` and freeze the ten violations** (`FreezingArchRule`) — rejected:
  it records the debt honestly and pays none of it, and the frozen file would have to survive the
  module fold that made the placement indefensible in the first place.
- **A second type: a domain `ConfigError` plus an adapter-private error per kind** — rejected as
  premature. Each factory may keep its own private failure vocabulary, but the *report* that
  reaches a *Surface* must be one shape, or every new *Version source* kind teaches itself to three
  *Surfaces* — the per-kind tax ADR-0034 has just removed.

## Consequences

- **`ConfigError`'s Javadoc is corrected**, because it is code, not a record. ADR-0032 keeps its
  body and gains a note pointing here, per the convention ADR-0034 set.
- **`ConfigErrors` (the CDI aggregator) is renamed `AggregatedConfigErrors`** and implements the
  `ConfigErrors` out-port. `ChangelogTemplates` keeps its name and implements `ChangelogLinks`.
  `ConfigErrorSource` stays in `adapters.out` — it is discovery machinery, not a contract the core
  needs.
- **`ScrapeStatus` stops exposing `ScrapeResult`.** The driving contract no longer names a driven
  type; the service unpacks the result itself. `ScrapeStatus.scraped` is called from nowhere else.
- **The scrape-state failure becomes a pair.** `ScrapeStateAccessException` (`core.ports.out`) is
  thrown by the store; `ScrapeStateUnavailableException` (`core.ports.in`) is what the use case
  raises, and keeps its name so the 503 mapper and its tests are unchanged. The two mean nearly the
  same thing; the return is that `adapters.in` never imports `core.ports.out`.
- **A runtime-proven config defect is still not a *Config error*.** A `regex` that matches nothing
  against a healthy endpoint stays a *Failed scrape* carrying no reason, exactly as ADR-0034
  accepted. Reporting it needs a per-scrape, per-side reason on `SideObservation` — mutable, and
  recomputed every scrape — which is a change to the scrape path, not to this type. A *Config
  error* remains what ADR-0032 defined: known when the fleet's sources are assembled, before any
  version is read.
- **`CONTEXT.md` is unchanged.** *Config error*, *Changelog link*, *Scrape state* and *Surface* are
  already defined and keep their meanings exactly. This ADR moves a type; it introduces no term.
- **`ApplicationConfigLoader` is untouched.** It owns the configuration document's shape, and
  translating that document into the data each adapter wants is its job.
