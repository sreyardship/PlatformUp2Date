# [03] Bearer auth + shared BearerAuthFilter

Status: done
Type: AFK

## Plan
See `../plan.md` for the shared architecture and
`../../docs/adr/0008-authenticated-http-current-source.md` for the rationale.

## What to build
Add `bearer` as a second auth scheme for the `http` current source, reusing one shared
bearer filter across both the current and latest legs.

- Generalize the existing `GithubAuthFilter` into a scheme-generic `BearerAuthFilter`
  (rename + drop GitHub-specific naming) that emits `Authorization: Bearer <token>`.
  Move the secret-exfiltration boundary documentation onto the *sources* that register
  it (each source owns "to whom do I send credentials"), since the filter is now generic.
- Repoint `GithubReleaseLatestSource` at `BearerAuthFilter` — no behavior change to the
  latest leg; it still authenticates the GitHub Releases API when a token is present.
  (Do NOT also migrate `GithubReleaseLatestSource` to the `CurrentVersionClientFactory`
  collaborator — it keeps its own lazy build; only the filter is shared.)
- In `HttpCurrentSourceFactory`, support `auth.type: bearer`: eager value-check requires
  a non-blank `token`; valid → construct `BearerAuthFilter`, build the client with it;
  missing/blank `token` → WARN + `FailedCurrentSource`, consistent with slice 02.

## Architectural surface
- Use cases: none (existing scrape loop unchanged).
- Ports: `CurrentVersionSource`, `LatestVersionSource` (both unchanged).
- Adapters: `BearerAuthFilter` (renamed/generalized from `GithubAuthFilter`),
  `GithubReleaseLatestSource` (uses `BearerAuthFilter`), `HttpCurrentSourceFactory`
  (bearer branch).

## Acceptance criteria
- [ ] `BearerAuthFilter` sets `Authorization: Bearer <token>` (unit-tested); the old
      `GithubAuthFilter` name no longer exists.
- [ ] `GithubReleaseLatestSource` uses `BearerAuthFilter`; `GithubReleaseLatestSourceIT`
      still passes (latest leg authenticates unchanged).
- [ ] Factory (POJO, fake collaborator): valid bearer → client built with a
      `BearerAuthFilter`; `bearer` with missing/blank `token` → `FailedCurrentSource` + WARN.
- [ ] Integration: a WireMock endpoint requiring `Authorization: Bearer` configured via
      `auth.type: bearer` yields its version; a blank token yields the clear
      `FailedCurrentSource` failure.
- [ ] The exfiltration-boundary documentation lives on the sources, not the now-generic
      filter.

## Blocked by
`01-http-current-source-pojo-collaborator.md`, `02-basic-auth-http-current.md`.
