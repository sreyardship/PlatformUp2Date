---
status: accepted
---

# Current-source tiers rank trust in the reading, not access

[`ARCHITECTURE.md`](../../ARCHITECTURE.md) tiers the current sources. The tiers
were first written around access: Tier A needed only the network, Tier B needed
a credential such as a ServiceAccount or an SSH key. That put `ssh-os-release`
in Tier B next to `k8s-image`, and hid the question the tier was meant to answer:
how much can the reading be trusted?

The tier now says **who wrote the version string**.

- **Tier A, self-reported.** The application wrote it about itself. An HTTP
  version endpoint, a `*_build_info` metric, a version header, and
  `/etc/os-release` are all Tier A. The transport does not matter.
- **Tier B, a deployed artifact, named by convention.** The label is on
  something that really runs, but it was set by a packaging step the
  application never saw. An image tag is the canonical case. It is right only
  while the naming convention holds.
- **Tier C, declared state.** Nothing observed. A manifest, a Helm value, a
  GitOps pin.

Access is still useful to an operator, so each source in
[`docs/configuration.md`](../configuration.md) says what it needs. It just no
longer decides the tier.

## Considered Options

- **Keep access as the axis** — rejected. `http-json` already takes a bearer
  token, so "no credentials" was never a clean line. And it ranked an OS's own
  statement about itself below an image tag, which is backwards.
- **Let a digest lift `k8s-image` to Tier A** — rejected. A digest cannot be
  moved to another build, but it names an image, not an application version.
  Mapping it to a version still goes through the tag convention.
  [ADR-0037](0037-build-identity-belongs-to-each-artifact.md) makes the same
  point from the other side: OCI tags and labels describe a deployment's claim,
  not what the artifact contains.

## Consequences

- `ssh-os-release` is relabelled Tier A. `k8s-image` stays Tier B.
- A future `ssh-command` source reading `<binary> --version` would be Tier A.
  One that asks the package manager would be Tier B.
- [ADR-0016](0016-ssh-os-release-current-source.md) calls node_exporter "the
  Tier-A, network-only path". That sentence uses the old axis. Under this one,
  both node_exporter and SSH are Tier A, and the choice between them is about
  what to install on the host, not about trust.
