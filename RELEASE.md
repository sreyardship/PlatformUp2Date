# Release flow

How PlatformUp2Date is built, published, and released. This describes the
intended flow; the CI internals and their rationale live in the workflow files
under `.github/workflows/` and in
[ADR 0023](docs/adr/0023-public-ci-on-github-actions-tekton-retired.md).

## Channels

Both images (`ghcr.io/sreyardship/platformup2date/backend` and
`.../frontend`) are always tagged in lockstep. What you can pull:

| Tag | Moves when | Meant for |
|---|---|---|
| `X.Y.Z` | never (immutable) | production — pin this |
| `X.Y`, `X`, `latest` | every release | tracking releases automatically |
| `edge` | every merge to `main` | trying the newest merged code |
| `sha-<short>` | never (immutable) | pinning an exact `main` commit |

Release-candidate tags (`vX.Y.Z-rc.N`) publish **only** their exact
`X.Y.Z-rc.N` image tag and a GitHub prerelease — they never move `latest`,
`X.Y`, `X`, or the deploy pins.

There is deliberately **no release per merge**: merges to `main` feed `edge`,
and a release happens only when a maintainer tags. `edge` plus the immutable
`sha-<short>` tags are the pre-release proving ground, so no separate rc
stream is published between releases.

## What each trigger does

- **Pull request** — tests only (JVM + native + manifest validation). PRs
  never build or publish images; fork PRs run with a read-only token
  (ADR 0023).
- **Merge to `main`** — builds both images once and publishes `edge` and
  `sha-<short>` (`.github/workflows/edge.yml`).
- **Push a `v*` tag** — the release pipeline (`.github/workflows/release.yml`):
  1. Builds and publishes the semver image tags for both images.
  2. Creates a GitHub Release: an Artifacts section (image pull coordinates
     for that exact version, pointer to the conf-check binary) followed by
     auto-generated release notes.
  3. Uploads the `conf-check` native binary (`conf-check-linux-amd64`) as a
     release asset. This job runs after the release is created, so the asset
     appears a few minutes late — known and accepted.
  4. Bumps the shipped image pins (`deploy/k8s/base/kustomization.yaml`,
     `compose.quickstart.yml`) to the released version with a direct commit
     to `main`. Skipped for prereleases.

## Cutting a release

The git tag is the single source of the version — nothing in the repo needs a
version bump first.

1. Make sure `main` is green (the `edge` publish for the tip commit
   succeeded).
2. Tag and push:

   ```bash
   git tag v0.2.0
   git push origin v0.2.0
   ```

3. Watch the *Release* workflow run; when it finishes, check the release page
   has the Artifacts section, the generated notes, and the
   `conf-check-linux-amd64` asset, and that the pin-bump commit landed on
   `main`.

To dry-run the pipeline without touching any rolling tag or pin, cut a
release candidate first (`git tag v0.2.0-rc.1 && git push origin
v0.2.0-rc.1`), verify, then push the real tag.

Versioning is semver, chosen at tag time by the maintainer: patch for fixes,
minor for features, major for breaking changes to the config schema or the
`/api/v1` contract. The project is in `0.x` — minor bumps may still carry
breaking changes.

## Deployment follows releases

Nothing pushes deploys. ArgoCD image-updater (or whatever the consumer runs)
watches the GHCR tags and picks up new versions on its own schedule
(ADR 0023) — a release is "live" only once the watcher has moved.
