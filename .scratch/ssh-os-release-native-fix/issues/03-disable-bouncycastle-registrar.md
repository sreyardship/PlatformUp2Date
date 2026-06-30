# [03] Shrink native metadata by disabling the BouncyCastle registrar

Status: dropped
Type: AFK

## Resolution: dropped (accepted fallback)
Attempted and abandoned per this slice's own fallback clause. Disabling MINA's BouncyCastle
registrar (`org.apache.sshd.registerBouncyCastle=false`) makes ed25519 unavailable: MINA SSHD
2.17 has no JDK-native EdDSA backend (its EdDSA support is BouncyCastle- or net.i2p-only,
despite JDK 25 having native EdDSA). With BC off, `SecurityUtils.isEDDSACurveSupported()` is
`false` and loading an OpenSSH ed25519 key throws
`NoSuchAlgorithmException: Unsupported key type (ssh-ed25519)`. Production host keys are
ed25519, so BouncyCastle is required and the slice-02 BC-inclusive metadata is retained. This
was confirmed on the JVM (no native build needed); see docs/adr/0018 (disable-BC option, now
rejected).

## Plan
See `../plan.md` for the shared architecture (domain, ports, adapters, test strategy).
Optional follow-on simplification — only attempt once slice 02's native guard is green.

## What to build
Reduce the native-image reflection surface (and therefore the upgrade-brittleness of the
committed metadata) by disabling MINA SSHD's BouncyCastle security-provider registrar, so
key-pair/security-entity resolution goes through the JDK provider's non-reflective
`getInstance(String)` path instead of the BouncyCastle named-provider path that triggered
the original `NoSuchMethodException`.

On JDK 21+ the JVM has native EdDSA, so ed25519 keys keep working without BouncyCastle.
Disable the registrar early enough to take effect before any SSHD security initialization
(e.g. via the `org.apache.sshd.security.registrars` system property or
`SecurityUtils.setRegisterBouncyCastle(false)` at startup). Re-run the tracing agent to
regenerate the now-smaller metadata and commit it. If — and only if — nothing else in the
codebase needs BouncyCastle after this, drop the `bcpkix` dependency.

Validation is entirely via slice 02's native `@QuarkusIntegrationTest`: the full
connect/auth/exec handshake against the embedded ed25519 server must still pass in native,
and OpenSSH private-key parsing for the (unencrypted) ed25519 key must still work. If the
native IT goes red and can't be made green with BC disabled, abandon this slice and keep the
BouncyCastle-inclusive metadata from slice 02 — that is the accepted fallback.

## Architectural surface
- Use cases: Scrape — unchanged behavior; validated by slice 02's guard.
- Ports: none changed.
- Adapters: `SshOsReleaseCurrentSource` / startup wiring (registrar disabled), regenerated
  `META-INF/native-image` metadata, optional removal of the `bcpkix` dependency.

## Acceptance criteria
- [ ] MINA's BouncyCastle registrar is disabled before any SSHD security initialization.
- [ ] Slice 02's native `@QuarkusIntegrationTest` still passes (ed25519 connect/auth/exec
      and OpenSSH key parsing work in native without BouncyCastle).
- [ ] The committed native-image metadata is regenerated and is no larger than before.
- [ ] `bcpkix` is removed only if no remaining code requires it; otherwise it stays with a
      note explaining why.
- [ ] If BC cannot be removed without breaking the native IT, the slice is dropped and the
      slice-02 metadata is retained (documented as the fallback).

## Blocked by
`02-native-image-ssh-guard.md` — the green native guard is the only thing that can prove
ed25519 still works with BouncyCastle disabled.
