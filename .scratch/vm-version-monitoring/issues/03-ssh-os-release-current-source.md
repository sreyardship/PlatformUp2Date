# [03] ssh-os-release current source

Status: done
Type: HITL

## Plan
See `../plan.md`. Settles [ADR-0016](../../../docs/adr/0016-ssh-os-release-current-source.md).

## What to build
A new current source that reads a VM's running version over SSH from
`/etc/os-release` — the first source that connects to and reads from a remote host,
so the host-key and key-credential boundary is the substance of the work.

- `ssh-os-release` source + factory (`type() = "ssh-os-release"`) using Apache MINA
  SSHD (`sshd-core`).
- Connect to `host` / `port` (default 22) as `user`; authenticate by private key,
  supplied either inline (`private-key`, env-expandable secret) or by path
  (`private-key-file`, read at connect for rotation) — exactly one, both-set refused,
  neither refused.
- Verify the server host key against an inline pinned `host-key` **or** a
  `known-hosts` file — exactly one, both-set refused, neither refused. A mismatch
  fails the source. No trust-on-first-use.
- Read `/etc/os-release` with a single fixed read (no config-supplied command),
  extract the `release-field` line (default `VERSION_ID`), strip quotes, and parse via
  the app `VersionParser`.
- Value/IO problems (unreachable host, key mismatch, missing field, unparseable value,
  bad key path) degrade to a failed source/scrape with a precise reason and are
  isolated per-app; only malformed config *structure* fails boot.
- Source is `Closeable`; the resolver closes it on shutdown.

**HITL:** confirm the MINA SSHD choice and review the new trust boundary, then
smoke-test against the live OpenWRT instance before sign-off.

## Architectural surface
- Use cases: none new.
- Ports: `CurrentVersionSource` (new adapter), `CurrentVersionSourceFactory` (new kind).
- Adapters: `SshOsReleaseCurrentSource`, `SshOsReleaseCurrentSourceFactory`;
  `ApplicationConfigLoader` gains `host`, `port`, `user`, `private-key`,
  `private-key-file`, `host-key`, `known-hosts`, `release-field`.

## Acceptance criteria
- [ ] An `ssh-os-release` app reads `VERSION_ID` from `/etc/os-release` and yields a
      `VersionValue` in the app's scheme.
- [ ] Host-key verification: pinned `host-key` and `known-hosts` both work; a mismatch
      fails the source; neither configured is refused; no TOFU.
- [ ] Key auth: inline `private-key` and `private-key-file` both authenticate;
      `private-key-file` is read at connect; both-set and neither-set are refused.
- [ ] Configurable `release-field` (default `VERSION_ID`); a missing field fails the
      source.
- [ ] Failures are isolated per-app (counted, reasoned); structural config errors
      fail boot; value/IO errors do not.
- [ ] Integration tests run against an embedded MINA SSHD server (Ubuntu + OpenWRT
      `/etc/os-release` fixtures, host-key-mismatch, both auth forms, bad field).
- [ ] Live smoke test against the real OpenWRT box passes.

## Blocked by
`01-versionvalue-and-version-scheme.md`. (A calver target also needs `02`; a semver
target does not.)
