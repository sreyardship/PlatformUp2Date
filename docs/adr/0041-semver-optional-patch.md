---
status: accepted
---

# Semver may omit the patch, per Application, under `semver-optional-patch`

Jellyfin dropped the leading `10.` with release 12.0 and now publishes two-digit
versions: tags `v12.0`, `v12.1`, `v12.2` and prereleases such as `v12.0-rc7`,
while its server API reports `12.2.0`. Its own 12.0 release notes say *"we are
now on two digits: the first digit is the major release... the second digit is
the minor release."* Strict semver rejects every two-part tag, so the latest leg
fails with "no parseable tag" forever, and no existing declaration fixes it
honestly.

We add a per-Application boolean, `semver-optional-patch`, default `false`, read
only under `version-scheme: semver`:

```yaml
- name: jellyfin
  version-scheme: semver
  semver-optional-patch: true
  current: { type: http-json, ... }                            # reports 12.2.0
  latest:  { type: github-release, repo: jellyfin/jellyfin }   # tags v12.2
```

## What the knob does

When `true`, `major.minor[-prerelease][+build]` is accepted alongside full
semver. A two-part string is completed to `major.minor.0` before the strict
semver parser sees it, so pre-release and build metadata attach to the padded
core exactly as semver expects (`12.0-rc7` → `12.0.0-rc7`). Everything that
follows is plain semver on the padded value: ordering, `equals`/`hashCode`,
Drift grading, pre-release handling.

- `v12.2` equals `12.2.0`; both order below `12.2.1`, which grades `PATCH`.
- `12.1` → `12.2` grades `MINOR`; `10.11.11` → `12.2` grades `MAJOR`.
- One part (`12`, Docker Hub's floating major tag) and four or more parts stay
  rejected.
- The published string is kept for display: `v12.2` shows as `12.2`, `12.2.0`
  as `12.2.0`. This is not `Semver.coerce` — nothing the operator reads is
  rewritten (ADR-0015's display-fidelity rule, as calver and dotnet follow).
- `{patch}` in a changelog template renders `0` for a two-part value, as
  `dotnet` renders an omitted build; `{version}` renders the published string.

Scrape state is untouched: stored values stay bare strings re-typed from config
on read (ADR-0022), so turning the knob on heals a stored two-part value on the
next read, without a migration.

## Why a knob on semver, not another scheme

ADR-0015's "explicit over implicit" is the reason: the config must name the
app's real versioning. Jellyfin declares major and minor in semver's sense; the
config should say `semver`, and say that the patch may be absent.

ADR-0036 rejected widening `SemverVersion` to a fourth component because that
would make it the union of two grammars and tax every semver app. This is the
opposite shape: tolerating an absent third component is a strict subset of the
same grammar, completed before the unchanged parser runs. It is opt-in per app,
so the strict default — and every existing app — is untouched.

## Equality follows the padded value

`12.2` and `12.2.0` are equal members of a set, consistent with ADR-0036's
"writing fewer digits is formatting, not precedence". Build-metadata significance
for `equals` is unchanged from today's semver value.

## Considered Options

- **Use `dotnet`** — rejected. It parses `12.2` mechanically, but Jellyfin never
  claimed `System.Version`, and the scheme brings a revision rule and has no
  pre-release concept, so `v12.0-rc7` would be unreadable and the config would
  say something false about the app.
- **Use `calver` with `MAJOR.MINOR`** — rejected. It names non-calendar
  versioning "calver", which ADR-0015 calls dishonest.
- **A fourth `major-minor` scheme** — rejected. It duplicates semver's
  ordering, pre-release and grading rules for the sake of one optional
  component, spending scheme budget ADR-0036 said a fourth scheme must argue for.
- **A generic positional `numeric` scheme** — rejected. It erases the meaning of
  each component, which Drift grading depends on, and invites every odd upstream
  to be declared as "just numbers".
- **Source-level tag rewriting** (a per-source regex or replace that turns
  `v12.2` into `12.2.0`) — rejected. It rewrites what the operator reads and puts
  a grammar rule in a source, where the two legs could disagree; the parser is
  the one place both legs share.
- **Relax semver globally** — rejected. Floating registry tags such as `1.27` or
  `12` would leak into every semver app's selection and could be chosen as
  latest.

## Consequences

- Semver gains one optional companion key, beside `calver-format` and
  `dotnet-compare-build`.
- An app with the knob on can see a two-part floating tag (e.g. a registry's
  `1.27`) as a version; that is why the knob is per app and off by default.
- Declaring the knob on a `calver` or `dotnet` app is meaningless; refusing that
  combination is a separate decision, recorded with its implementation.
