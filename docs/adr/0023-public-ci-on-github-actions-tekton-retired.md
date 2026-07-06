# Public CI on GitHub Actions; Tekton retired entirely

The project is moving from a private Gitea remote to a public GitHub repo
(github.com/sreyardship/PlatformUp2Date). Until now CI was Tekton on the
author's cluster, pushing images to a private registry — the repo's history is
saturated with that setup (`ci/` pipelines, sealed secrets, registry env). A
public repo changes the threat model: CI must run *contributors'* code, and a
fork PR flowing into cluster-hosted Tekton would execute outsider-controlled
Gradle builds inside private infrastructure. So all CI moves to GitHub Actions:
PR checks (JVM tests plus the GraalVM native build + native ITs, both required
status checks — native breakage must block the merge, per the ADR 0025 guard
philosophy), `main` merges publishing `:edge` to GHCR, and `v*` tags publishing
semver images (`ghcr.io/sreyardship/platformup2date/{backend,frontend}`) with a
GitHub Release. Fork PRs run on ephemeral GitHub-owned runners with a read-only
token, and tag publishing uses the built-in `GITHUB_TOKEN` — no long-lived
credentials anywhere.

Tekton is not merely relocated but retired: the private deploy path becomes
pull-based GitOps — ArgoCD-image-updater watches the GHCR packages and bumps
tags in the deployment repo, so nothing on the cluster receives webhooks from
GitHub at all. The `ci/` directory is deleted.

## Considered Options

- **Keep Tekton, expose it to GitHub PRs (rejected).** Requires an
  internet-facing EventListener and runs untrusted fork code next to the
  cluster's secrets; contributors also can't see logs or rerun checks.
- **Gitea primary with a GitHub read-only mirror (rejected).** Preserves the
  Tekton setup untouched, but PRs and issues opened on the mirror go nowhere —
  hostile to the contributors the public release is meant to attract.
- **GitHub Actions for everything, ArgoCD image-updater for deploy (chosen).**
  See above.

## Consequences

- Every PR waits on the native build (~15–20 min on free public runners)
  before it can merge. Accepted deliberately: native-image regressions must be
  caught pre-merge, not at release time.
- Deploys track published GHCR tags, so a release is not deployed until
  image-updater picks it up — there is no push-triggered deploy anymore.
- The oci-registry version source can monitor this project's own GHCR
  packages, since releases are exactly semver image tags (ADR 0014's
  largest-semver rule applies cleanly).
