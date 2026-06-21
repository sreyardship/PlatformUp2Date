# The `github-release` latest source is configured by `repo` (owner/repo), not a full `url`

Every `github-release` app config repeated the GitHub API host as a hand-typed
`url: https://api.github.com/repos/{owner}/{repo}`. The app's own `name` (e.g.
`git-tea`, `harbor`) cannot stand in for this on its own — the GitHub owner and
repo name routinely diverge from the app name (`git-tea` → `go-gitea/gitea`,
`harbor` → `goharbor/harbor`) — so deriving the URL requires a value distinct
from `name`. We replace `url` with a new field, `repo: {owner}/{repo}` (e.g.
`repo: go-gitea/gitea`), matching the shape already sketched in
`ARCHITECTURE.md`'s original design. `GithubReleaseLatestSourceFactory` now
requires a non-blank `repo` containing exactly one `/`, builds
`https://api.github.com/repos/{repo}`, and passes that constructed URL into the
unchanged `GithubReleaseLatestSource` exactly as before. `url` is no longer
accepted for this source kind — a breaking change applied to all four existing
app configs in the same change.

## Considered Options

- **Derive the URL from the app's `name`** — rejected: the GitHub owner/repo
  routinely diverges from the app name, so this can't work without also renaming
  apps and somehow inferring the owner.
- **Keep `url`, add `repo` as an optional shortcut** — rejected: two ways to
  configure the same thing invites ambiguity about precedence; cleaner to have
  one way and migrate the (small, fully-owned) existing config.
- **Make the GitHub API host configurable** (e.g. a `base-url` field) for GitHub
  Enterprise support — rejected: nothing in this project targets a self-hosted
  GitHub, and `GithubReleaseLatestSource`'s existing residual assumption (ADR
  0010) already trusts that `latest` always points at github.com.
- **Push the `repo` → URL construction into `GithubReleaseLatestSource` itself**
  — rejected: the source is constructed directly (with an arbitrary base URL)
  by `GithubReleaseLatestSourceIT` against a WireMock stub. Hardcoding the host
  inside the source would break that test-construction path; keeping the
  source's constructor as a plain base-URL string and confining the
  `repo`-to-URL translation to the factory's `create()` preserves it.

## Consequences

- `GithubReleaseLatestSourceFactory.create()`'s validation error message and
  tests change from "requires url" to "requires repo" / "repo must be owner/repo".
- `GithubReleaseLatestSource` itself is untouched — it still takes a base URL
  string — so `GithubReleaseLatestSourceIT` and `GithubReleaseLatestSourceTests`
  need no changes.
- All four app configs in `application.yml` (`argo-cd`, `git-tea`, `sharry`,
  `harbor`) migrate their `latest.url` to `latest.repo`.
- `ApplicationConfigLoader.VersionSource` gains a `repo()` leaf, read only by
  this source kind (mirroring how `pageSize()`/`versionKey()` are already
  kind-specific optional leaves on the shared tagged-union interface).
