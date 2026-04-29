# CI/CD — Tekton on Kubernetes
## Overview

Pipelines in `ci/` are triggered by Gitea webhooks via an event listener. Two pipelines handle different stages of the workflow:

- **`pr-build-test`** — triggered when a PR is opened/updated; clones, builds, tests, and publishes a prerelease container image tagged with the sanitized branch name (e.g. `0.0.2-feature-cool-thing.0`).
- **`main-release`** — triggered on push to `main` (after PR merge); retags the prerelease image to a stable version (e.g. `0.0.2`) without rebuilding.

Webhook payloads are validated via a Tekton GitHub interceptor (HMAC signature check). Manifests are managed with Kustomize.

## Infrastructure Split

CI infrastructure is split between two repositories:

- **Platform-owned** (in `jumziCluster/apps/service-spaces/platformup2date/`): namespace, secrets, RBAC, EventListener, Istio policies, HTTPRoute. Managed by ArgoCD via the `service-spaces` Application.
- **Developer-owned** (this directory): pipeline definitions and trigger templates. Synced by ArgoCD via the `platformup2date-ci` Application defined in the platform repo.

The developer should not need to modify namespace, secrets, service accounts, or networking config — those are managed by the platform team.

## Key Files

- **PR pipeline:** `ci/pipelines/pr-pipeline.yaml`
- **Main release pipeline:** `ci/pipelines/main-pipeline.yaml`
- **Trigger templates & bindings:** `ci/triggers/trigger-template.yaml`
- **CI scripts:** `backend/ci/bin/` — individual scripts executed by pipeline tasks (`build.sh`, `build-native.sh`, `unit-test.sh`, `retag-release.sh`). Future frontend CI scripts should go under `frontend/ci/bin/`.

## Pipeline Task Graphs

### PR pipeline (`pr-build-test`)
```
set-pending-status (context: ci/pr-build-test)
git-clone
├── build-backend-jar → unit-test
└── build-backend-native → build-and-publish-container (prerelease: branch_name)
finally: pipeline-state
```

### Main pipeline (`main-release`)
```
set-pending-status (context: ci/main-release)
git-clone → retag-container (skopeo copy prerelease → stable)
finally: pipeline-state
```

## Versioning

- **PR builds:** tagged `X.Y.Z-<branch-name>.N` where N auto-increments. The `container-build-push` cluster task handles this via the `prerelease` param.
- **Main releases:** tagged `X.Y.Z`. The `retag-release.sh` script queries the registry for the latest stable tag and increments the patch number.
- **Minor/major bumps:** include `[minor]` or `[major]` in the merge commit message to override the default patch bump.

## Adding a New CI Step

All CI scripts are executed via the `nix-devshell-run` cluster task (namespace: `tekton-pipelines`). To add a new step:

```yaml
- name: my-new-task
  runAfter:
    - git-clone          # or whichever task it depends on
  taskRef:
    resolver: cluster
    params:
      - name: name
        value: nix-devshell-run
      - name: namespace
        value: tekton-pipelines
  params:
    - name: script
      value: backend/ci/bin/my-script.sh
  workspaces:
    - name: source
      workspace: source
```

Use `runAfter` to control ordering. Tasks with the same `runAfter` dependency run in parallel.

## Dev/CI Parity

The Nix flake in `project-environment/` provides the same dependencies locally as in CI. The `nix-devshell-run` cluster task uses this flake, so local dev shell and CI tasks always share the same toolchain.

**IMPORTANT:** CI scripts in `backend/ci/bin/` must be runnable locally by a normal (non-root) user inside the Nix devshell (`nix develop ./project-environment`). Do not add commands that require root privileges or write to system paths like `/etc/`. Use user-local paths (e.g. `$HOME/.config/`) instead.
