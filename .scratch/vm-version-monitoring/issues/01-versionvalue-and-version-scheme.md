# [01] VersionValue abstraction + per-app version-scheme plumbing

Status: done
Type: AFK

## Plan
See `../plan.md` for the shared architecture (domain, ports, adapters, test strategy).
Settles [ADR-0015](../../../docs/adr/0015-calver-as-a-first-class-version-scheme.md).

## What to build
The tracer bullet: introduce the version abstraction the rest of the work hangs off,
with semver as the only implementation, and prove it composes end-to-end with **no
behavior change** for any existing app.

- Turn today's concrete `Version` into a sealed `VersionValue` interface (operations:
  `isOlderThan`, `diff`, `value`, `withoutPreRelease`; the `Diff` enum moves onto it),
  with `SemverVersion` carrying the current semver4j logic verbatim.
- Add a `VersionScheme` enum (`SEMVER | CALVER`) and a `VersionParser` domain service
  that, for now, parses only `SEMVER` (calver lands in slice 02). One parser is built
  per app and used by both legs.
- Add an optional app-level `version-scheme` config field defaulting to `semver`.
- Thread the parser through the composition root: the factory SPI becomes
  `create(cfg, parser)`; `VersionSourceResolver` builds the per-app parser and passes
  it in; existing sources (`http`, `k8s-image`, `github-release`) produce
  `VersionValue` via the parser instead of `new Version(...)`.
- Update every `Version` / `Version.Diff` reference (`VersionApplication`, the MCP
  tools, the Prometheus renderer, the HTTP/MCP view adapters) to the new types.

When done, every existing app scrapes, compares, and grades exactly as before, and a
config can set `version-scheme: semver` explicitly to no effect.

## Architectural surface
- Use cases: none new (existing scrape loop unchanged).
- Ports: `CurrentVersionSource`, `LatestVersionSource` (return `VersionValue`);
  `CurrentVersionSourceFactory`, `LatestVersionSourceFactory` (SPI `create(cfg, parser)`).
- Adapters: existing `http`, `k8s-image`, `github-release` sources/factories adapt to
  the new SPI; `VersionSourceResolver` builds and threads the parser.

## Acceptance criteria
- [ ] `VersionValue` sealed interface with `SemverVersion`; `Diff` enum relocated and
      all references compile against the new home.
- [ ] `VersionScheme` enum and `VersionParser`; parser selects `SemverVersion` for
      `semver` and is the single per-app instance shared by both legs.
- [ ] `version-scheme` config field added to `AppConfig`, defaulting to `semver`.
- [ ] Factory SPI is `create(cfg, parser)`; resolver builds the parser per app and
      passes it to both current and latest factories.
- [ ] Existing sources construct versions through the parser, not `new Version(...)`.
- [ ] The existing `Version` test suite is ported onto `SemverVersion` and passes
      unchanged in meaning.
- [ ] All pre-existing unit/integration/system tests (scrape loop, MCP, metrics,
      controller) are green — behavior is provably unchanged.

## Blocked by
None — can start immediately.
