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

- **Pull request** — tests only (JVM + native + frontend build/image checks +
  manifest validation). PRs build local test images but never publish them;
  fork PRs run with a read-only token (ADR 0023).
- **Merge to `main`** — builds both images once and publishes `edge` and
  `sha-<short>` (`.github/workflows/edge.yml`).
- **Push a `v*` tag** — the release pipeline (`.github/workflows/release.yml`):
  1. Builds and publishes the semver image tags for both images.
  2. Creates a GitHub Release: an Artifacts section (image pull coordinates
     for that exact version) followed by auto-generated release notes.
  3. Bumps the shipped image pins (`deploy/k8s/base/kustomization.yaml`,
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
   has the Artifacts section and the generated notes, and that the pin-bump
   commit landed on `main`.

To dry-run the pipeline without touching any rolling tag or pin, cut a
release candidate first (`git tag v0.2.0-rc.1 && git push origin
v0.2.0-rc.1`), verify, then push the real tag.

Versioning is semver, chosen at tag time by the maintainer: patch for fixes,
minor for features, major for breaking changes to the config schema or the
`/api/v1` contract. The project is in `0.x` — minor bumps may still carry
breaking changes.

## Embedded build identity

Each artifact reports its own build identity: backend `GET /api/version` and
frontend `GET /version.json`, both returning `{"version":"..."}`. The frontend
does not ask the backend for its version.

| Build | Embedded identity |
|---|---|
| Release tag `v1.2.3` | `1.2.3` |
| Prerelease/build metadata `v1.2.3-rc.6+build.1` | `1.2.3-rc.6+build.1` |
| CI edge | `dev-<full git rev-parse HEAD>` of the checked-out source |
| Local build without injection | `dev` |

The shared publishing workflow resolves identity once **after checkout**, using
[`ci/resolve-build-version.sh`](ci/resolve-build-version.sh). Release tags must
be strict SemVer with a leading `v`; only that prefix is removed. Both image
jobs check out the resolved commit and consume the same identity:

- Backend: `gradle ... -PbuildVersion=<identity> -PbuildVersionRequired=true`, before
  native compilation, not in the later Docker packaging step.
- Frontend Docker build arguments: `BUILD_VERSION=<identity>` and
  `BUILD_VERSION_REQUIRED=true`, before static asset generation.

Both publishing channels require explicit valid injection and fail rather than
falling back to `dev`. Runtime environment variables, monitoring config, OCI
labels, and image retagging cannot change the embedded identity. Build metadata
is preserved in responses; image tags still follow docker/metadata-action's
existing rules (OCI tag syntax does not permit `+`).

For a local injected build, run Gradle from the repository root or Docker with
the frontend directory as context:

```bash
gradle :backend:quarkusBuild -PbuildVersion=1.2.3-rc.6+build.1 -PbuildVersionRequired=true
docker build frontend --build-arg BUILD_VERSION=1.2.3-rc.6+build.1 \
  --build-arg BUILD_VERSION_REQUIRED=true
```

PR checks test the identity CLI, rebuild the JVM artifact with changed injection
without cleaning generated output, query its public endpoint, and exercise the
frontend build and nginx images. Native integration tests use explicit
injection through the packaged native image. Those tests validate the source
and image recipe, **not the exact binary later published**: the publishing run
separately recompiles the triggering source with the release/edge identity.
Tag pushes rebuild both images; they do not promote or retag an earlier `edge`
artifact. Publication tag policies and the pin-bump flow above are unchanged.

## Deployment follows releases

Nothing pushes deploys. ArgoCD image-updater (or whatever the consumer runs)
watches the GHCR tags and picks up new versions on its own schedule
(ADR 0023) — a release is "live" only once the watcher has moved.
