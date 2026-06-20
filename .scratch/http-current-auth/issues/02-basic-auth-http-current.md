# [02] Basic auth on the HTTP current source (Harbor case study)

Status: done
Type: AFK

## Plan
See `../plan.md` for the shared architecture and
`../../docs/adr/0008-authenticated-http-current-source.md` for the rationale.

## What to build
End-to-end HTTP Basic authentication for the `http` current source, delivering the
Harbor case: `container-registry.sreyardship.com/api/v2.0/systeminfo` returns
`harbor_version` only when authenticated.

- Extend the config union with a nested auth fragment on `VersionSource`:
  `auth` with a **required** `type` discriminator and optional `username` / `password`
  (and `token`, reserved for slice 03), all resolved via SmallRye env expansion
  (`${HARBOR_USER:}` / `${HARBOR_PASS:}`). Because `type` is required, an `auth:` block
  with no `type` is *structurally* malformed and fails SmallRye binding at boot.
- Add a `BasicAuthFilter` (`ClientRequestFilter`) that emits
  `Authorization: Basic base64(username:password)`.
- In `HttpCurrentSourceFactory`, after the existing url/pointer checks, perform an
  **eager value-check** when `auth` is present:
  - `type: basic` with both username and password set and non-blank → construct the
    `BasicAuthFilter`, build the client with it (via the collaborator from slice 01),
    return `HttpCurrentSource`.
  - unknown `type`, or `basic` with a missing/blank username or password (e.g. an unset
    env var resolving to `""`) → log a clear WARN at startup naming the app and the
    problem, and return a new `FailedCurrentSource` whose `version()` throws that same
    clear message every scrape. The bad app fails in isolation; the rest of the fleet
    scrapes normally.
- Add the Harbor entry to the dev `application.yml` (`version-key: /harbor_version`,
  `strip-prerelease: true` per ADR-0007, `auth: {type: basic, username: ${HARBOR_USER:},
  password: ${HARBOR_PASS:}}`).

The credential is sent to the app's configured `url` with no host check — the
documented GitHub-style residual assumption (see ADR-0008); document it on the source.

## Architectural surface
- Use cases: none (existing scrape loop unchanged).
- Ports: `CurrentVersionSource` (unchanged; new `FailedCurrentSource` implements it).
- Adapters: `BasicAuthFilter` (new), `FailedCurrentSource` (new),
  `HttpCurrentSourceFactory` (auth resolution + WARN), `ApplicationConfigLoader`
  (nested `Auth`), `CurrentVersionClientFactory` (filter now passed through).

## Acceptance criteria
- [ ] `VersionSource` carries `Optional<Auth> auth()` with a required `String type()`
      and optional env-expandable `username()/password()/token()`; an `auth:` block
      without `type` fails to bind at boot.
- [ ] `BasicAuthFilter` sets `Authorization: Basic <base64(user:pass)>` exactly
      (unit-tested).
- [ ] `FailedCurrentSource.version()` throws its carried clear message (unit-tested).
- [ ] Factory (POJO, fake collaborator): valid basic → client built with a
      `BasicAuthFilter`; unknown `type` → `FailedCurrentSource` + WARN; basic with
      missing/blank username or password → `FailedCurrentSource` + WARN.
- [ ] Integration: a Harbor-shaped WireMock endpoint requiring Basic auth, configured
      via `auth`, yields `harbor_version`; with a blank credential the app fails with the
      clear `FailedCurrentSource` message — NOT the misleading "version-key did not
      resolve" error.
- [ ] A failing-auth app reports per-app FAILED while other apps scrape (isolation),
      verified through the existing scrape/version controller ITs.
- [ ] Dev `application.yml` includes the Harbor app entry.

## Blocked by
`01-http-current-source-pojo-collaborator.md`.
