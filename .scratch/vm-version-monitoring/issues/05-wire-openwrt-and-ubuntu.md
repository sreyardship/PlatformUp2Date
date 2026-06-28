# [05] Wire real OpenWRT + Ubuntu apps and live-verify

Status: ready-for-agent (config drafted; live verification pending)
Type: HITL

> Progress: commented OpenWRT + Ubuntu app blocks and the production ConfigMap shape
> (private-key-file / host-key mounts) are drafted in `backend/src/main/resources/application.yml`.
> Remaining HITL work, on the user's hardware: provision the monitoring SSH key + pinned host
> keys, fill in real host/host-key values, enable the blocks, and verify both apps show correct
> isOld()/drift() against the live OpenWRT box and an Ubuntu VM.

## Plan
See `../plan.md`. Exercises all three ADRs (0015/0016/0017) together end-to-end.

## What to build
Compose the two real targets as monitored Applications and verify the whole path —
calver scheme + `ssh-os-release` current + `http-regex` latest — produces correct
up-to-date / behind status against live hardware.

- An **OpenWRT** app: `version-scheme: calver`, `calver-format: "YY.0M.MICRO"`,
  current `ssh-os-release` (the live box, pinned host key, key auth), latest
  `http-regex` against the OpenWRT releases listing.
- An **Ubuntu** app: `version-scheme: calver`, `calver-format: "YY.0M"`, current
  `ssh-os-release`, latest `http-regex` against `meta-release-lts`.
- Wire them into the dev config and document the shape for the production ConfigMap
  (mounted keys / host keys, the same way the commented k8s example documents its
  shape today). No new code is expected beyond config and fixtures unless live
  verification surfaces a gap.

**HITL:** needs the user's hardware — the live OpenWRT instance and an Ubuntu VM to
set up — plus provisioning of the SSH key and host key. Verification is manual:
confirm both apps appear with correct `isOld()`/`drift()` and that `VERSION_ID`
(`24.04`) compares equal to the `meta-release-lts` latest when current.

## Architectural surface
- Use cases: existing scrape loop, unchanged.
- Ports/Adapters: composition only — `ssh-os-release` (03), `http-regex` (04), and the
  `calver` scheme (02), assembled via the resolver from config.

## Acceptance criteria
- [ ] OpenWRT app: current read over SSH, latest from the releases listing, correct
      green/red against the live box.
- [ ] Ubuntu app: current `VERSION_ID` over SSH, latest from `meta-release-lts`,
      LTS-to-LTS comparison; an up-to-date box shows no drift.
- [ ] Production ConfigMap shape documented (key/host-key mounts), consistent with the
      existing in-cluster examples.
- [ ] A full scrape including both apps stays within per-app failure isolation (one
      unreachable host never sinks the fleet).

## Blocked by
`02-calver-version-value.md`, `03-ssh-os-release-current-source.md`,
`04-http-regex-latest-source.md`.
