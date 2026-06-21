# 01 github-release configured by repo slug, not full URL

Status: done
Type: AFK

## Plan
See `../plan.md` for the shared architecture (domain, ports, adapters, test strategy) and
`docs/adr/0011-github-release-configured-by-repo-slug.md` for the settled decision and
rejected alternatives.

## What to build
Today every `github-release` latest-version source is configured with a full GitHub API
URL (`url: https://api.github.com/repos/{owner}/{repo}`), hand-typed per app. Replace this
with a `repo: {owner}/{repo}` field (e.g. `repo: go-gitea/gitea`) — the GitHub host is no
longer something config authors type out.

`GithubReleaseLatestSourceFactory.create()` changes its validation: it now requires a
non-blank `repo` matching the `owner/repo` shape (exactly one `/`), and fails fast with a
clear message naming `repo` when it's absent, blank, or has the wrong number of slashes
(zero or more than one). On success, it builds the full GitHub API URL itself
(`https://api.github.com/repos/` + the configured repo) and passes that into the
**unchanged** `GithubReleaseLatestSource` constructor exactly as it does today for a
configured `url`. The host is hardcoded in this one place only — `GithubReleaseLatestSource`
itself is not touched, so it still accepts an arbitrary base-URL string and the existing
WireMock-backed integration test keeps working unmodified.

`url` is no longer read by the `github-release` kind. It remains a valid field on the shared
`ApplicationConfigLoader.VersionSource` tagged-union interface (the `http` current source
still uses it) — only the `github-release` factory's behavior changes.

Migrate all four existing apps in `application.yml`'s `%dev` profile (`argo-cd`, `git-tea`,
`sharry`, `harbor`) from their `latest.url` to the equivalent `latest.repo`:
- `argo-cd` → `repo: argoproj/argo-cd`
- `git-tea` → `repo: go-gitea/gitea`
- `sharry` → `repo: eikek/sharry`
- `harbor` → `repo: goharbor/harbor`

## Architectural surface
- Use cases: none (unaffected).
- Ports: none changed. `LatestVersionSourceFactory` keeps its existing `type()` +
  `create(cfg)` shape.
- Adapters: `GithubReleaseLatestSourceFactory` (behavior change — validation + URL
  construction). `ApplicationConfigLoader.VersionSource` (interface change — new `repo()`
  leaf). `GithubReleaseLatestSource` is explicitly NOT changed.

## Acceptance criteria
- [ ] `ApplicationConfigLoader.VersionSource` exposes `Optional<String> repo()`.
- [ ] `GithubReleaseLatestSourceFactory.create()` throws `IllegalArgumentException` with a
      message naming `repo` when `repo` is absent or blank.
- [ ] `create()` throws `IllegalArgumentException` when `repo` has zero `/` (e.g. `"gitea"`)
      or more than one `/` (e.g. `"a/b/c"`).
- [ ] `create()` succeeds for a well-formed `owner/repo` value and the resulting source, when
      asked to read a version, hits `https://api.github.com/repos/{owner}/{repo}/releases`
      (verify by inspection of the constructed URL passed to `GithubReleaseLatestSource`, or
      by extending coverage if it's easy to observe directly — implementer's judgement; the
      existing `GithubReleaseLatestSourceIT` must NOT need to change).
- [ ] Page-size validation (1–100, default 30) keeps working unchanged.
- [ ] `application.yml`'s four apps (`argo-cd`, `git-tea`, `sharry`, `harbor`) use `repo:`
      instead of `url:` under `latest`, with the correct owner/repo values listed above.
- [ ] A configured `url` under `latest` for a `github-release` app is no longer read — `repo`
      is the only field consulted.
- [ ] `GithubReleaseLatestSourceFactoryTests` is updated: `url`-focused validation tests
      (`create_rejectsAbsentUrl…`, `create_rejectsBlankUrl…`, and the `source(...)` test-double
      builder) become `repo`-focused, plus new tests for the zero-slash and multi-slash
      rejection cases.
- [ ] `GithubReleaseLatestSourceTests` and `GithubReleaseLatestSourceIT` are unchanged and
      still pass.
- [ ] `gradle test` passes.

## Blocked by
None — can start immediately.
