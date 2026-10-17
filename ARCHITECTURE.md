# Architecture

This document captures the **leading design thought** behind PlatformUp2Date: why it is
built the way it is, and the principles that any new feature should be measured against.
For *how* the backend is laid out (hexagonal packages, build commands), see `CLAUDE.md`.

## Guiding idea: a general, substrate-agnostic version monitor

PlatformUp2Date answers one question per application: **"is it running the latest
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
repo pin. Keep this in mind if we ever implement a Tier C adapter.

## Tiers of "current" probes (ordered by trust)

The tier says **who wrote the version string** the probe reads. The closer the author
is to the application, the more the reading can be trusted. Each application picks the
best tier it can support. Most apps only ever need Tier A, and that is awesome.

### Tier A — self-reported

The application wrote the version string about itself. Nothing sits between the probe
and the author. Transport does not matter: HTTP, a metrics page, or SSH are all Tier A
when the application is the author.

Possible adapters (not all implemented at the time of writing):

- **HTTP version endpoint** — `GET <url>` and pick a field (e.g. JSON Pointer `/version`).
- **Prometheus `*_build_info` scrape** — read `/metrics`, take the `version=` label.
- **HTTP header** — `Server:`, `X-Version`, or another custom header.
- **HTML/regex scrape** — `<meta name="generator">`, a footer or login-page version string.
- **`/etc/os-release` over SSH** — the distro wrote it, so it is the OS's own statement.
- **`<binary> --version` over SSH** — the same idea with another transport.

### Tier B — a deployed artifact, named by convention

The probe reads a label on an artifact that really got deployed. But the label was set
by a packaging step the application never saw. The reading is right only as long as
everyone follows the naming convention.

- **Kubernetes image tag** — read the *running* pod's container image tag.
- **Package manager version** — the package wraps the binary; the two can disagree.

An image tag can be moved to another build. A digest cannot, but a digest names an
image, not an application version, so it does not lift the probe to Tier A. When the
app has a Tier A source, use that instead.

Kubernetes is one optional adapter among many here — never the foundation.

### Tier C — declared state, explicit last resort

Nothing here has been observed running. Use it only when nothing observable is
reachable, and **always label it "declared, may differ from running".**

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

- `CurrentVersionSource` → `HttpCurrentSource`, `K8sImageCurrentSource`,
  `SshOsReleaseCurrentSource`, …
- `LatestVersionSource` → `GithubReleaseLatestSource`, `OciRegistryLatestSource`,
  `HttpRegexLatestSource`, …

The core service (`ApplicationVersionService`) stays oblivious to deployment substrate; it
only asks "current?" and "latest?" and compares. Each application is configured as simply
`{ current: { type, ... }, latest: { type, ... } }`, so supporting a new deployment style
or a new upstream is *one new adapter*, with no change to the core.

### Resulting config shape

The syntax as implemented today (see [`docs/configuration.md`](docs/configuration.md)
for every source type and key):

```yaml
platform-config:
  scrape-interval: 1h
  apps:
    - name: git-tea
      current:
        type: http-json              # Tier A
        url: https://git.sreyardship.com/api/v1/version
        version-key: /version
      latest:
        type: github-release
        repo: go-gitea/gitea

    - name: rook-ceph
      current:
        type: k8s-image              # Tier B — the image tag, set by packaging
        namespace: storage-operator
        workload: deployment/rook-ceph-operator
        container: rook-ceph-operator
      latest:
        type: github-release
        repo: rook/rook

    - name: edge-router
      current:
        type: ssh-os-release         # Tier A — the OS reports its own version
        host: router.example.com
        user: pu2d
        private-key-file: /secrets/router_ed25519
        known-hosts: /secrets/known_hosts
      latest:
        type: http-regex             # scrape the vendor's release page
        url: https://vendor.example.com/downloads
        regex: 'Latest:\s*v?(\d+\.\d+\.\d+)'
```
