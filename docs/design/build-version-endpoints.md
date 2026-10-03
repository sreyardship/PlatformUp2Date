# Backend and frontend build-version endpoints

Status: implemented and locally verified: JVM tests, injected packaged native
image tests, frontend builds, both nginx configurations, and build-identity
regeneration checks.

## Goal and scope

Publish the running backend's version and the frontend-serving service's own
version so either can be monitored as an ordinary Application. Each artifact
reports its own identity: the frontend must not obtain its version from the
backend, even though both images are published in lockstep.

Self-monitoring is opt-in configuration, not a special feature of the scrape
loop. Provide an example using existing `http-json` current sources and
`github-release` latest sources. Do not automatically add Applications to the
shipped monitoring configuration.

No new version source, UI version display, MCP tool, build-info metric, or
installation-wide version aggregation is included.

## HTTP contract

| Service | Request | Meaning |
|---|---|---|
| Backend | `GET /api/version` | Build version of the backend instance answering the request. |
| Frontend | `GET /version.json` | Build version embedded in the frontend artifact served by nginx. |

Both return HTTP 200 with `Content-Type: application/json`,
`Cache-Control: no-store`, and a single string field:

```json
{"version":"1.2.3"}
```

Both are anonymously readable, including when web or MCP Surface authentication
is enabled. No credentials, configuration, or additional build metadata are
exposed. An operator's external authentication proxy may still restrict access;
its bypass rules are separate from the application's authentication policy.

The existing `/api/v1/version` continues to return monitored Applications'
statuses. It is not repurposed. The new backend endpoint reads only embedded
build identity: no scrape, Valkey access, or upstream request is needed. The
frontend endpoint works independently of backend availability.

Each response identifies the instance that answered, not all replicas or a
browser's already-loaded JavaScript bundle. During a rolling update, successive
requests may legitimately return different versions. No rollout aggregation or
replica-consistency check is added. Existing readiness behavior is unchanged;
a backend with failing readiness may be unreachable through its Service even
though serving build identity itself needs no Valkey access.

## Version identity

| Build | Reported value |
|---|---|
| Release tag `v1.2.3` | `1.2.3` |
| Prerelease tag `v1.2.3-rc.6` | `1.2.3-rc.6` |
| CI edge build | `dev-<commit>` |
| Local development build | `dev` |

The release tag is the source of truth. Remove its leading `v`; preserve the
remaining version, including prerelease and build metadata. Embed the value at
build time in both artifacts. A runtime environment variable or mounted
monitoring configuration must not override it. Retagging an existing image must
not change the version it reports.

`dev` and `dev-<commit>` deliberately do not pretend to be semantic releases.
The ordinary semver source cannot successfully read them as comparable current
versions. Stable-version monitoring examples target tagged builds; there is no
special coercion or fabricated release number for edge/local builds.

## Fit with existing CI

The checked-in workflows already rebuild both images from the triggering
release tag's source; they do not promote or merely retag an earlier `edge`
image:

- [`release.yml`](../../.github/workflows/release.yml) calls the shared image
  builder for `v*` tag pushes.
- [`edge.yml`](../../.github/workflows/edge.yml) calls it for pushes to `main`.
- [`build-images.yml`](../../.github/workflows/build-images.yml) checks out the
  event's source, compiles the native backend, and builds the frontend image.
- [`frontend/Dockerfile`](../../frontend/Dockerfile) runs `yarn build` before
  copying the result into nginx's document root.

The shared builder now resolves identity once after checkout using
[`ci/resolve-build-version.sh`](../../ci/resolve-build-version.sh), then both
image jobs check out that exact commit and consume the same output. The backend
receives `-PbuildVersion=<identity> -PbuildVersionRequired=true` before native compilation;
the frontend Docker build receives `BUILD_VERSION=<identity>` and
`BUILD_VERSION_REQUIRED=true` before asset generation. Package versions and
OCI labels are not the release authority for these endpoints.

Preserve the current publication flow and tag policies. Resolve the build
identity before compiling the backend native executable and before building
the frontend assets. The backend's existing image-metadata step runs **after**
native compilation; adding a label or Docker argument at that point cannot
embed a value in the executable. Pass the same resolved identity into both
builds, with edge identity based on the commit actually being built.

Release builds must not silently fall back to `dev` or the existing snapshot
version when version injection is missing or invalid. Treat build identity as
a build input so cached/generated outputs cannot retain an earlier version.
PR checks must exercise injection without publishing images.

## Implementation boundaries

- Add a separate backend HTTP resource for build identity. Keep it independent
  of monitoring state and services. Follow the dependency rules enforced by
  `HexagonalArchitectureTests`; any cross-layer collaboration goes through
  `core/ports`.
- Use immutable generated build data rather than a runtime-configurable version
  value. Ensure the chosen representation is available in both JVM and native
  builds.
- Generate the frontend JSON as part of the build and package it with that
  build's static assets. Do not generate identity from container-start
  environment variables or from the backend API. Local serving reports `dev`.
- Add an exact `/version.json` location in both
  [`frontend/nginx.conf`](../../frontend/nginx.conf) and
  [`frontend/nginx.quickstart.conf`](../../frontend/nginx.quickstart.conf).
  Serve JSON with `no-store`; a missing file must return 404 rather than fall
  through to the SPA's `index.html` with HTTP 200.
- Preserve the existing routing contract: `/api` goes to the backend unstripped;
  other paths go to the frontend. Neither endpoint needs a new ingress rule.
- Preserve `/api/v1*` and `/api/mcp*` role gates. Verify anonymous access to
  `/api/version` with authentication enabled rather than relying only on its
  path being outside those prefixes.
- Keep REST code-first. The backend resource and DTO define its contract, and
  SmallRye generates OpenAPI; do not author an OpenAPI file. The SPA does not
  consume this endpoint, so no frontend API client is needed for this feature.

## Opt-in monitoring example

This example is for use after the endpoints ship. Replace the hostname with
one reachable from the scraper, or use each service's internal address with
the same endpoint paths. Merge these entries into the operator's own config;
do not replace an existing fleet unintentionally.

```yaml
platform-config:
  scrape-interval: 1h
  apps:
    - name: platformup2date-backend
      current:
        type: http-json
        url: https://platformup2date.example.com/api/version
      latest:
        type: github-release
        repo: sreyardship/PlatformUp2Date

    - name: platformup2date-frontend
      current:
        type: http-json
        url: https://platformup2date.example.com/version.json
      latest:
        type: github-release
        repo: sreyardship/PlatformUp2Date
```

Both use the existing defaults: JSON Pointer `/version` and the `semver`
version scheme. They share an upstream release repository but have independent
current observations.

The existing GitHub Releases source selects the largest parseable version from
non-draft, non-prerelease releases in its configured recent window (default 30).
It does not monitor the RC channel. If no eligible release exists in that
window, the latest scrape fails; without a previous successful latest read,
the Application remains Unresolved. An endpoint can report an RC correctly
without the latest source selecting RCs. No source-selection behavior changes.

## Acceptance and verification

1. Backend JVM tests assert the exact response shape, content type, `no-store`,
   and independence from scrape state. Regress the existing fleet endpoint.
2. Authentication-on coverage proves anonymous build-version access while the
   existing REST and MCP role gates remain enforced.
3. Native integration coverage checks an explicitly injected version through
   the packaged backend image, proving build metadata survives native compilation
   and packaging. Runtime configuration cannot replace that identity.
4. Frontend build and nginx image tests check injected release/RC values, the
   local default, JSON headers, missing-file 404, and both nginx configurations.
   Demonstrate frontend identity remains available with the backend unavailable.
5. Build-wiring tests cover release, prerelease, and edge identity propagation,
   and rebuilding with a changed version without stale generated output. Verify
   both images independently report the expected identity; labels alone are
   insufficient evidence.
6. Verify the example through the ordinary `http-json` source with controlled
   release data. Keep GitHub latest-source policy unchanged.
7. Check generated backend OpenAPI and run all directly affected suites,
   including native/image coverage. Follow [`CONTRIBUTING.md`](../../CONTRIBUTING.md)
   and [the PR workflow](../../.github/workflows/pr.yml) for build roots and gates.

## Documentation when implemented

Update [`RELEASE.md`](../../RELEASE.md) with build-time injection and the
release/edge/local identity rules; remove workflow comments saying release tags
are intentionally not injected. Describe rebuilds accurately: PR native tests
do not test the exact executable later compiled by a separate publishing run.

Add the example and development-version limitations to
[`docs/configuration.md`](../configuration.md). Document public endpoint paths,
cache/proxy considerations, and instance-local identity in
[`docs/deployment.md`](../deployment.md). Keep the shipped monitoring defaults
unchanged. The operator documents now describe this contract. Completion still requires
all endpoint and artifact acceptance checks above.

## Rationale and related decisions

- Build-time identity observes the running artifact rather than a deployment's
  claimed version, matching [`ARCHITECTURE.md`](../../ARCHITECTURE.md).
- Separate identities avoid falsely claiming frontend/backend deployment
  synchronization just because releases are published together.
- Public minimal endpoints avoid credentials for ordinary version discovery;
  putting the backend route outside `/api/v1` preserves the existing role gate.
- Static frontend JSON avoids a backend dependency and fits existing routing.
- No self-monitoring special case is needed: the existing version sources
  already express this configuration.

Related ADRs: [0010 — GitHub latest selection](../adr/0010-github-release-latest-is-largest-semver.md),
[0020 — code-first API](../adr/0020-api-is-code-first.md),
[0023 — CI and publication](../adr/0023-public-ci-on-github-actions-tekton-retired.md),
[0028 — Surface authentication](../adr/0028-web-and-mcp-surfaces-role-gated-behind-one-issuer.md),
and [0037 — artifact-local build identity](../adr/0037-build-identity-belongs-to-each-artifact.md).
