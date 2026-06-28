---
status: proposed
---

# A VM's current version is read over SSH from /etc/os-release, with mandatory host-key verification

To monitor whole VMs (Ubuntu, OpenWRT) we need their running OS version, and a VM
does not publish that over HTTP. Both distros ship `/etc/os-release` with a clean,
comparable `VERSION_ID` (`24.04`, `23.05.5`). We add an `ssh-os-release` current
source that reads it. This is the **first source that executes on a remote host**,
so it introduces a credential and trust boundary the existing HTTP/k8s sources
never had — that boundary is the substance of this decision.

```yaml
current:
  type: ssh-os-release
  host: router.lan
  port: 22                              # optional, default 22
  user: monitor
  private-key: ${SSH_MONITOR_KEY:}      # inline secret, OR:
  private-key-file: /secrets/ssh/key    # path, read at connect
  host-key: "ssh-ed25519 AAAA..."       # pinned, OR:
  known-hosts: /secrets/ssh/known_hosts # file path
  release-field: VERSION_ID             # optional, default VERSION_ID
```

The decision splits along several points:

**Host-key verification is mandatory; trust-on-first-use is refused.** A scraper
that auto-accepts an unknown host key trusts whatever answers on that address.
Verification is therefore required: either an inline pinned `host-key` or a
`known-hosts` file, exactly one of the two. A mismatch, or neither configured,
degrades to `FailedCurrentSource` (logged WARN), never silent acceptance. TOFU is
rejected outright — it needs persisted per-host state to pin against, and this
service is deliberately stateless across replicas ([ADR-0004](0004-mcp-transport-stateless-for-ha.md)),
so there is nowhere every replica agrees on to remember "the key I saw first."

**Key auth only, inline or file, mirroring the bearer pattern.** Authentication
is by private key — no password auth, so no reusable host password sits in config.
The key is supplied either inline (`private-key`, env-expandable secret, captured
at boot) or by path (`private-key-file`, read at connect so a rotated key on disk
is picked up without restart), exactly one of the two. This deliberately mirrors
the `token` / `token-file` split of the bearer auth in
[ADR-0012](0012-http-current-source-file-token-and-custom-ca.md): both-set is
refused as ambiguous, neither-set is refused.

**A fixed, structured read — not arbitrary remote execution.** The source runs a
single safe read of `/etc/os-release` and extracts one `KEY="value"` line named by
`release-field` (default `VERSION_ID`), stripping the quotes. Config carries no
shell command, so there is no command-injection surface and the credential can be
locked to a forced command host-side. A general "run any command + regex" probe is
a *different* future kind (`ssh-command`), added as its own factory if a real
`<binary> --version` need appears — not built speculatively here.

## Considered Options

- **node_exporter / Prometheus `node_os_info`** — rejected as the first build. It
  is the Tier-A, network-only path the architecture prefers and needs no SSH, but
  it requires node_exporter deployed on every monitored VM — unnatural for a
  home OpenWRT router, and still "set something up per host." SSH covers both
  targets with one adapter and nothing installed on them. node_exporter remains a
  reasonable future current source.
- **OpenWRT ubus/LuCI JSON-RPC over HTTP** — rejected: solves OpenWRT cleanly but
  is OpenWRT-only and leaves the common Ubuntu case unsolved, needing a second
  transport anyway.
- **Trust-on-first-use host keys** — rejected: incompatible with the stateless,
  multi-replica design and weak security (trusts first contact).
- **Password authentication** — rejected: stores reusable host passwords; a scoped
  key is a better boundary and both distros take keys fine.
- **Arbitrary `command` + regex now** — deferred: more rope than the stated need,
  and a real remote-exec surface. Kept as a future `ssh-command` kind, open-closed.

## Consequences

- The backend gains an SSH client dependency and a new outbound trust boundary
  (host keys, private keys) that operators must provision per host — the cost of
  reaching non-HTTP VMs.
- The host key is pinned in the same config that adds the app, keeping "add a
  monitored host = one ConfigMap edit" with no per-host file to mount when the
  inline form is used; the `known-hosts` form trades that for the familiar SSH
  idiom.
- Per-app failure isolation is preserved: an unreachable host, a key mismatch, a
  missing `release-field`, or an unparseable value fails that one app's scrape and
  is counted, exactly as an unreachable HTTP endpoint already is.
- `release-field` defaults to `VERSION_ID` but is configurable for the next distro
  that stores its comparable version under a different key (`BUILD_ID`, `VERSION`).
