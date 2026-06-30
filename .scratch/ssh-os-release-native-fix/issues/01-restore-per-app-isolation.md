# [01] Restore per-app isolation for ssh-os-release

Status: done
Type: AFK

## Plan
See `../plan.md` for the shared architecture (domain, ports, adapters, test strategy).

## What to build
Make a failing `ssh-os-release` source fail only its own app's scrape — counted as a per-app
FAILED `TargetResult` — instead of crashing the whole fleet scrape (and NPE-ing
`get_application`).

Today `SshOsReleaseCurrentSource` builds and starts the MINA `SshClient` in its
*constructor*, which runs at `VersionSourceResolver` build time. Any environmental failure
there (the native-image reflection crash, an unreachable host at boot, etc.) escapes the
per-app isolation boundary and aborts the use case for every app.

Move all SSH I/O — client build, start, connect, auth, exec — into `version()`, built and
released per call with try-with-resources. The constructor only stores config and
collaborators (host, port, user, key loader, server-key verifier, release field, parser).
`close()` becomes a no-op since there is no longer a long-lived client. Keep the factory's
validation split exactly as documented: blank/absent host or user still throws from the
factory to fail the deploy fast; ambiguous/missing key and host-key config still degrade to
`FailedCurrentSource`. This is a JVM-verifiable slice; it does not by itself make SSH work
in the native image (that is slice 02) — it ensures the SSH apps degrade to FAILED in native
rather than taking the fleet down.

## Architectural surface
- Use cases: Scrape (full/targeted) in `ApplicationVersionService` — unchanged, now isolated.
- Ports: `CurrentVersionSource` (contract clarified: `create()` is side-effect-free; all
  SSH setup and environment-dependent failures occur in `version()`).
- Adapters: `SshOsReleaseCurrentSource` (per-call client lifecycle), `SshOsReleaseCurrentSourceFactory`
  (unchanged behavior), `VersionSourceResolver` (unchanged — relies on cheap `create()`).

## Acceptance criteria
- [ ] `SshOsReleaseCurrentSource`'s constructor builds/starts no `SshClient` and performs no
      network or security-provider initialization.
- [ ] All connect/auth/exec happens inside `version()`, with the client created and closed
      per call (try-with-resources); `close()` on the source is a no-op.
- [ ] A forced client-build/connect failure surfaces as an exception thrown from `version()`,
      not from the constructor or from `factory.create(...)`.
- [ ] Factory behavior is unchanged: blank/absent host or user throws; both/neither
      private-key(-file) and both/neither host-key/known-hosts return `FailedCurrentSource`.
- [ ] The existing in-process `SshOsReleaseCurrentSourceIT` happy-path, key-file,
      known-hosts, host-key-mismatch, and release-field tests stay green.
- [ ] A unit/integration test demonstrates that when one app's current source throws from
      `version()`, other apps in the same scrape still produce results (fleet not aborted).

## Blocked by
None — can start immediately.
