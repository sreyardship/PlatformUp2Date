# [01] HttpCurrentSource becomes a POJO behind a client-building collaborator

Status: done
Type: AFK

## Plan
See `../plan.md` for the shared architecture (domain, ports, adapters, test strategy)
and `../../docs/adr/0008-authenticated-http-current-source.md` for the rationale.

## What to build
A pure refactor with **no new behavior and no auth** — the architecture spike that
proves the new composition (and the eager-build-at-boot risk) before any feature
breadth is added.

Today `HttpCurrentSource` builds its own Quarkus REST client lazily on first
`version()`, mixing two concerns: extracting a version from a JSON response (pure
logic) and constructing an Arc-bound REST client. Split them:

- Introduce an `@ApplicationScoped` collaborator that builds a `CurrentVersionClient`
  for a given base URL (registering the existing `VersionResponseExceptionMapper`),
  exposing a build operation that also accepts an optional client request filter
  (unused this slice — wired in 02/03). This is the only Arc-bound piece.
- Make `HttpCurrentSource` a pure POJO: it receives a ready `CurrentVersionClient`
  plus the `version-key` pointer and `strip-prerelease` flag, and does only
  `JsonNode.at(pointer)` extraction + optional prerelease stripping (including the
  existing clear, truncated-body error when the pointer misses or isn't textual).
  No `QuarkusRestClientBuilder`, no `URI`, no Arc.
- `HttpCurrentSourceFactory` injects the collaborator and builds the client
  **eagerly** in `create(cfg)`, handing the ready client to the source. Its existing
  url + JSON-Pointer validation is unchanged, and it stays POJO-testable via a fake
  collaborator.

Every existing app must scrape exactly as before. The `Closeable` lifecycle owned by
`VersionSourceResolver` must keep working (the client is still closed on shutdown).

Prove the eager-build risk here: real REST-client construction now happens during CDI
bean construction at boot. If startup ordering misbehaves, move the build to a
`@PostConstruct`/startup event — do NOT reintroduce per-scrape laziness.

## Architectural surface
- Use cases: none (existing scrape loop unchanged).
- Ports: `CurrentVersionSource` (unchanged).
- Adapters: `HttpCurrentSource` (refactored POJO), `CurrentVersionClientFactory`
  (new, Arc-bound), `HttpCurrentSourceFactory` (inject collaborator, eager build).

## Acceptance criteria
- [ ] `HttpCurrentSource` is constructible as a plain POJO with a `CurrentVersionClient`
      and is unit-tested without Arc/WireMock (pointer hit, strip-prerelease, missing /
      non-textual pointer → clear truncated-body error).
- [ ] A `CurrentVersionClientFactory` (`@ApplicationScoped`) builds a working client for
      a base URL with the exception mapper registered; integration-tested against WireMock.
- [ ] `HttpCurrentSourceFactory` builds the client via the collaborator (eagerly) and
      keeps its existing url/version-key validation; its unit tests remain POJO (fake
      collaborator), no `@QuarkusTest`.
- [ ] All existing tests pass unchanged in behavior, including `HttpCurrentSourceIT`
      and the scrape/version controller ITs; no app's scrape result changes.
- [ ] The app boots with the eager build for every configured app; if startup ordering
      requires it, the build is relocated to `@PostConstruct`/startup event (documented
      in the class), never to per-scrape laziness.
- [ ] Source `Closeable` lifecycle still closes the client on shutdown.

## Blocked by
None — can start immediately.
