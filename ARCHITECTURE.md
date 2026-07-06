# Architecture

This document captures the **leading design thought** behind PlatformUp2Date: why it is
built the way it is, and the principles that any new feature should be measured against.
For *how* the backend is laid out (hexagonal packages, build commands), see `CLAUDE.md`.

## Guiding idea: a general, substrate-agnostic version monitor

PlatformUp2Date answers one question per application: **"is what's running the latest
version, or is it behind?"** The hard part is that the applications it watches are not
uniform. They are deployed in every way imaginable and many of them are not ours:

- Apps we deploy via GitOps (ArgoCD/Kustomize/Helm) in our own cluster.
- Apps other teams run, where we have no access to their deployment repo.
- Apps on plain VMs, bare metal, or weird legacy boxes.
- Hosted/third-party things we can only reach over the network.
- Routers! They are weird.

The tool must work for **all** of them. Therefore the core design rule is:

> **Make no assumption about how or where an application is deployed.**

Everything else follows from that rule.

## "Current" means *observed running state*, never *declared state*

It is tempting to read the deployment source — the GitOps repo pin, the Helm values, the
manifest image tag — and call that the current version. We deliberately **do not** treat
that as the source of truth, because declared state lies about reality:

- GitOps drifts; syncs fail; a controller is paused or broken.
- Someone runs a manual `kubectl apply`, or a team uses CI-apply pipelines instead of a
  reconciler, so nothing continuously enforces the repo.
- We frequently have no access to the deployment source at all.

So the definition we hold to is:

> **"Current" = what the running instance reports about itself, observed from the outside.**

Declared-state inference is allowed only as an explicit, clearly-labeled last resort
(see Tier C), and the UI should distinguish an *observed* reading from a *declared* guess
so a green card from a real version endpoint is never conflated with a green card from a
repo pin. Keep this is mind if we ever implement a tier C Adapter.

## Tiers of "current" probes (ordered by required access)

Each application picks the best probe it can support. Most apps only ever need Tier A, and that is awesome.

### Tier A — black-box, network reachability only (the default)

Substrate-agnostic. Works whether the app runs in k8s, on a VM, or on the moon (assuming the moon gets hooked up to the world wide web), because it only talks to the running app over the network.

A list of possible adapters (not all implemented at the time of writing)
- **HTTP version endpoint** — `GET <url>` and select a field (e.g. JSONPath `$.version`).
- **Prometheus `*_build_info` scrape** — hit `/metrics`, read the `version=` label.
- **HTTP header probe** — `Server:`, `X-Version`, or other custom headers.
- **HTML/regex scrape** — `<meta name="generator">`, a footer or login-page version string.
- *(extensible: any "fetch text → regex → version" probe also covers TLS banners,
  `/version.txt`, etc.)*

### Tier B — credentialed, opportunistic (only when we happen to have access)

Still observes *reality*, not the manifest, but does not talk to the application directly necessarily.

- **Kubernetes API** — read the *running* pod's container image tag, or better its image
  **digest** (tags can be mutated; digests cannot).
- **SSH to a host** — `<binary> --version`, or query the package manager.

Kubernetes is just *one optional adapter among many here* — never the foundation.

### Tier C — declared state, explicit last resort

Used only when nothing observable is reachable, and **always labeled "declared, may differ
from running."**

- GitOps repo pin, Helm values, or a manifest image tag.

## "Current" and "latest" are fully decoupled

The way we learn what is *running* has nothing to do with the way we learn what is
*available upstream*. They are two independent, pluggable probes per application. An app
discovered via an HTTP endpoint may compare against GitHub releases; an app read from the
Kubernetes API may compare against a registry's tag list. Any combination is valid.

Upstream ("latest") probe sources include:

- GitHub / GitLab / Gitea **releases**.
- Container **registry tag lists** (GHCR / Quay / Docker Hub).
- Helm repository **`index.yaml`** (compare `appVersion`, not just chart `version`).
- Generic **scrape page + regex** for vendors without a clean API.

## How this shapes the code

The hexagonal backend expresses the above as two out-ports, each with many adapters,
selected per application by a discriminated config:

- `CurrentVersionProbe` → `HttpEndpointProbe`, `PrometheusBuildInfoProbe`,
  `HttpHeaderProbe`, `HtmlRegexProbe`, `KubernetesPodProbe`, …
- `LatestVersionProbe` → `GithubReleasesProbe`, `RegistryTagsProbe`, `HelmIndexProbe`,
  `HtmlRegexProbe`, …

The core service (`ApplicationVersionService`) stays oblivious to deployment substrate; it
only asks "current?" and "latest?" and compares. Each application is configured as simply
`{ current: { type, ... }, latest: { type, ... } }`, so supporting a new deployment style
or a new upstream is *one new adapter*, with no change to the core.

### Resulting config shape

```yaml
platform-config:
  scrape-interval: 1h
  apps:
    - name: git-tea
      current:
        type: http-endpoint          # Tier A
        url: https://git.sreyardship.com/api/v1/version
        field: $.version
      latest:
        type: github-releases
        repo: go-gitea/gitea

    - name: rook-ceph
      current:
        type: k8s-image              # Tier B — reads the RUNNING pod
        namespace: storage-operator
        selector: app=rook-ceph-operator
      latest:
        type: github-releases
        repo: rook/rook

    - name: some-legacy-vm-app
      current:
        type: html-regex             # Tier A — scrape the login page
        url: https://legacy.example.com/login
        regex: 'v(\d+\.\d+\.\d+)'
      latest:
        type: html-regex
        url: https://vendor.example.com/downloads
        regex: 'Latest:\s*v?(\d+\.\d+\.\d+)'
```
