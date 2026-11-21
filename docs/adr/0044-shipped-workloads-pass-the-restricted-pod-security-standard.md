---
status: accepted
---

# Shipped workloads pass the `restricted` Pod Security Standard

The shipped Deployments set no `securityContext`. A namespace that enforces the
`restricted` Pod Security Standard rejected the pods. Each shipped container
(backend, frontend, bundled Valkey) now sets `allowPrivilegeEscalation: false`,
drops all capabilities, sets `runAsNonRoot: true`, and uses the `RuntimeDefault`
seccomp profile.

The profile does not require a read-only root filesystem. The backend and the
frontend set `readOnlyRootFilesystem: true` nevertheless, because each of them
needs only `/tmp` to be writable. The cost was different for each container:

- **Backend.** The image already runs as UID 1001. With a read-only root
  filesystem and no writable `/tmp`, the backend does not start: Vert.x creates
  its file cache in `/tmp/vertx-cache` at start. The Deployment mounts an
  `emptyDir` at `/tmp`.
- **Frontend.** The image used `nginx:alpine`. It ran as root, listened on
  port 80, and wrote `env-config.js` into the static file directory at start.
  The image now uses `nginxinc/nginx-unprivileged:alpine`. It runs as UID 101,
  listens on port 8080, and keeps the nginx pid file and temp paths in `/tmp`.
  The served `env-config.js` is a symbolic link to `/tmp/env-config.js`. The
  Deployment mounts an `emptyDir` at `/tmp`. The Service still exposes port 80.
- **Valkey.** The upstream image has no `USER`. It starts as root and its
  entrypoint changes to the `valkey` user, which needs capabilities. The
  manifest sets `runAsUser: 999` and `runAsGroup: 1000`, the IDs of that user
  in the alpine image. Its root filesystem stays writable. The profile does
  not require a read-only one, and the default configuration writes snapshots
  to `/data`.

A PR check applies the quickstart overlay to a kind cluster as a server-side
dry run. Pod Security admission rejects only Pods in `enforce` mode, and a
Deployment with a bad pod template is admitted. Thus, the check labels the
namespace with `warn` also and runs `kubectl` with `--warnings-as-errors`. A
control Deployment with no `securityContext` must fail first, so the check
cannot pass on a cluster that does not evaluate the profile.

## Considered Options

- **Serve `env-config.js` from `/tmp` with an nginx `location` (rejected).**
  The path would then be part of each nginx configuration. The compose quick
  start mounts its own configuration over the one in the image, and it can pin
  an image from before this change, which writes the file to the static file
  directory. With the link, the configuration does not know where the file is,
  so the two image generations work with the same configuration.
- **Create the link in the runtime stage (rejected).** The runtime stage has
  no `RUN` step, so that a multi-arch build needs no emulation (ADR-0043). The
  link is created in the build stage and copied.
- **Disable the Vert.x file cache instead of a writable `/tmp` (rejected).**
  This is a change to the backend build, with behavior that must be proved in
  the native image. An `emptyDir` is a change to the manifest only, and it
  also covers other temp files.
- **Start the pods in the PR check (rejected for now).** It is the only proof
  that the images run with these limits. But the backend image of a PR exists
  only in the native jobs. The frontend image check starts the frontend with
  the same limits (`--read-only`, `--tmpfs /tmp`, `--cap-drop ALL`,
  `no-new-privileges`). The backend limits were proved once, by hand, on a
  kind cluster that enforces the profile.

## Consequences

- The frontend image listens on port 8080, not port 80. This is a breaking
  change for operators who run the image with their own manifest or port
  mapping. The shipped Service keeps port 80, so Ingress and HTTPRoute rules
  do not change.
- The manifests and the frontend image must come from the same release. The
  manifests do not start an image from before this change, because that image
  runs as root. The image pins in `deploy/k8s/base/kustomization.yaml` must
  point at an image with this change before the manifests are released.
- The entrypoint of the nginx image runs `40-env-config.sh` only when the
  container command starts with `nginx`. A command that does not start with
  `nginx` leaves the link without a target. `compose.yml` thus starts nginx
  through `/docker-entrypoint.sh`.
- The Valkey IDs are those of the `8-alpine` image. A different image can need
  different IDs.
- The backend has no automatic check for the read-only root filesystem. A new
  dependency that writes outside `/tmp` fails only in a cluster.
