---
status: accepted
---

# Images are multi-arch; each native binary is compiled on its own architecture

The published images were `linux/amd64` only. They did not run on Raspberry Pi
and other ARM boards, or on ARM cloud nodes, which a large part of the home lab
audience uses. Both images now publish `linux/amd64` and `linux/arm64` as one
manifest list per tag, on every channel (`edge`, `sha-<short>`, semver).

The frontend is simple: its build output is static assets, so one job builds
both platforms. The build stage runs on the build platform and the runtime
stage has no `RUN` step, so no emulation is necessary.

The backend is not: GraalVM `native-image` cannot cross-compile, so an arm64
binary must be compiled on arm64. We compile each binary on a GitHub-hosted
runner of its own architecture. Each job pushes its image by digest, without a
tag. One last job joins the two digests into a manifest list and writes the
tags.

Three rules follow:

- **A tag moves only when both architectures are built.** The job that writes
  the backend tags needs every per-architecture job; the frontend tags come
  from one multi-platform push. A tag never means "amd64 only today".
- **The shipped Dockerfile selects the ELF loader from the target
  architecture** and fails the build for an architecture it does not know. The
  `patchelf` stage stays because binaries built in the Nix devshell point into
  `/nix/store`.
- **PR checks run the native integration tests on both architectures.** The
  publish workflows run only on `main` and on tags, so these jobs are the only
  pre-merge proof that the arm64 binary compiles and that its image boots on
  the UBI9 runtime. The runner images are pinned, to the same pair for PR
  checks and for publishing, because the binary links against the build host's
  glibc.

## Considered Options

- **Compile the arm64 binary under QEMU emulation on an amd64 runner
  (rejected).** `native-image` is CPU and memory heavy, and user-mode emulation
  multiplies its build time; this was not measured for this project. Native
  arm64 runners are free for public repositories, and a test on emulated
  hardware is weaker proof than a test on the real architecture.
- **Per-architecture tags such as `edge-arm64` (rejected).** Operators would
  have to select a tag per node, and the shipped manifests and the quickstart
  pin one tag for all nodes.
- **Publish the architectures that built and skip the one that failed
  (rejected).** The same tag would then run on some nodes and fail to pull on
  others, with no signal in the tag.
- **Publish arm64 without arm64 PR checks (rejected).** The first proof of an
  arm64 regression would then be a failed publish on `main`, or a release that
  does not start.

## Consequences

- Each PR waits for two native builds. They run in parallel, so the waiting
  time is that of the slower one.
- Publishing depends on the availability of GitHub-hosted arm64 runners.
- A failed publish run can leave an untagged per-architecture image in GHCR.
  No tag points at it.
- The architecture pair is named in several places: the PR and publish
  workflows (matrices, digests, frontend platforms, platform check), the
  Dockerfile loader map, and its test. A new architecture needs a native
  runner and a change in each of them.
- `linux/arm/v7` (32-bit ARM) stays unsupported: GraalVM has no `native-image`
  target for it.
