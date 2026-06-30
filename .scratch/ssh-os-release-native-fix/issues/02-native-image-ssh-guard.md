# [02] ssh-os-release resolves a version in the native image, guarded

Status: done
Type: HITL

## Plan
See `../plan.md` for the shared architecture (domain, ports, adapters, test strategy).

## What to build
Make the `ssh-os-release` source actually read a version over SSH **in the GraalVM native
image**, and lock it in so a future regression fails the build rather than reaching prod.

Two parts that land together because each is worthless without the other:

1. **The guard.** Add a `@QuarkusIntegrationTest` in the `integrationTest` source set,
   driven by a `QuarkusTestResourceLifecycleManager` that boots an embedded **ed25519**
   MINA SSH server (server + client ed25519 keys, matching the production host-key type),
   serves the fixed `cat /etc/os-release` command, and injects the server's
   host/port/host-key/private-key into an `ssh-os-release` app's configuration before the
   app boots. The test triggers a scrape of that app through the running application and
   asserts the app resolves its current `VERSION_ID`. Because the existing
   `build-backend-native` pipeline task runs `quarkusIntTest` against the freshly built
   native binary, this exercises connect/auth/exec **in native**.

2. **The metadata.** Run the GraalVM tracing agent against the SSH path on the JVM (using
   the same embedded-server harness) to capture reflection/resource/proxy reachability, and
   commit the generated config under `backend/src/main/resources/META-INF/native-image/`.
   This is what makes the native IT pass. Iterate native builds until the IT is green.

Also extend the in-process `SshOsReleaseCurrentSourceIT` fixtures to ed25519 so JVM coverage
matches the prod key type (not just RSA).

Marked HITL: generating metadata via the agent and iterating minutes-long native builds is a
human-in-the-loop chore (deciding which entries to keep, re-running on failures).

## Architectural surface
- Use cases: Scrape (full/targeted) — exercised end-to-end through the native binary.
- Ports: `CurrentVersionSource` (no change; relies on slice 01's per-call lifecycle).
- Adapters: `SshOsReleaseCurrentSource` (now succeeds in native), new `QuarkusTestResource`
  embedded ed25519 SSH server + new `@QuarkusIntegrationTest`, committed
  `META-INF/native-image` reachability metadata.

## Acceptance criteria
- [ ] A `@QuarkusIntegrationTest` boots an embedded ed25519 MINA server via a
      `QuarkusTestResource`, injects its coordinates/keys into an `ssh-os-release` app, and
      asserts a scrape resolves the expected `VERSION_ID`.
- [ ] The test runs in the existing native `quarkusIntTest` stage and passes against the
      native binary (connect/auth/exec succeed in native).
- [ ] GraalVM reachability metadata is committed under
      `backend/src/main/resources/META-INF/native-image/` and is what makes the native IT pass.
- [ ] `SshOsReleaseCurrentSourceIT` covers ed25519 server + client keys in addition to RSA.
- [ ] Deleting/corrupting the committed metadata makes the native IT fail (the guard is real).
- [ ] Documented (PR description or a short note): how to regenerate the metadata with the
      tracing agent when MINA SSHD is upgraded.

## Blocked by
`01-restore-per-app-isolation.md` — the per-call client lifecycle must be in place so the
source builds the client inside `version()` (where the native path is exercised) rather than
at resolver-build time.
